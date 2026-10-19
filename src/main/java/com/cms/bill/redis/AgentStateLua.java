package com.cms.bill.redis;

/**
 * 坐席事件原子处理 Lua 脚本。
 *
 * <p>所有校验、窗口闭合、统计累加、状态写入在一个脚本内完成，天然串行，无需分布式锁。</p>
 *
 * <p>可靠性设计（参考 Avaya CMS/ECHI 的做法）：</p>
 * <ul>
 *   <li>事件幂等去重：SET NX 占位，重复事件直接确认（对应 ECHI 重传识别）</li>
 *   <li>事件序列号防护：提供 seq 时按 32 位回绕语义校验单调递增（对应 ECHI SEQUENCE_NUMBER
 *       4 字节序号计满回绕归零），状态 hash 维护 lastEventSeq 与 seqWrapCount（回绕累计）</li>
 *   <li>时钟偏差防护：事件时间超前服务器时间超过阈值则拒绝，允许修正后重推</li>
 *   <li>乱序不倒退状态机：事件时间早于当前状态进入时间时，仅记录观测字段，
 *       不闭合窗口、不转移状态、不重置进入时间（对应 CMS 迟到事件只记录不改变状态机）</li>
 *   <li>同状态转移忽略：同状态事件不切分时长窗口、不重复计数（对应 CMS AUX→AUX 不产生时长切分）</li>
 *   <li>状态 hash 带 TTL 并随事件续期，防止僵尸状态</li>
 * </ul>
 *
 * <p>KEYS：
 * <ol>
 *   <li>坐席状态 hash key</li>
 *   <li>事件去重 key（无 eventId 时传占位 key，且 ARGV hasEventId=0）</li>
 *   <li>事件日期的统计 hash key</li>
 *   <li>上一窗口起始日期的统计 hash key（用于跨天窗口拆分）</li>
 *   <li>当日活跃坐席 set key</li>
 *   <li>全量坐席 set key</li>
 * </ol>
 * ARGV：
 * <ol>
 *   <li>hasEventId（0/1）</li>
 *   <li>dedupTtlSeconds</li>
 *   <li>eventId</li>
 *   <li>eventTimeMs（事件时间戳）</li>
 *   <li>newState（事件目标状态名）</li>
 *   <li>eventTypeName</li>
 *   <li>stateEnterTimeAssumedMs（乐观校验值，读取状态时的快照；-1 表示尚无状态）</li>
 *   <li>eventDateMidnightMs（事件当天零点 epoch 毫秒，用于跨天拆分）</li>
 *   <li>statsTtlSeconds</li>
 *   <li>activeSetTtlSeconds</li>
 *   <li>agentId</li>
 *   <li>callId（空串表示无）</li>
 *   <li>serverNowMs</li>
 *   <li>hasSeq（0/1，是否携带事件序列号）</li>
 *   <li>seq（事件序列号，hasSeq=0 时忽略；内部按 mod 2^32 归一化为 32 位）</li>
 *   <li>stateTtlSeconds（状态 hash 保留时长，每次事件续期）</li>
 *   <li>maxFutureSkewMs（允许的事件时间超前服务器时间的最大毫秒数）</li>
 * </ol>
 * 返回数组：{code, prevState, closedWindowMs, outOfOrder, sameState, finalState}
 * <ul>
 *   <li>code 0=成功 1=重复事件 2=状态快照过期需重试
 *       3=序列号过旧被忽略 4=事件时间超前过多被拒绝</li>
 *   <li>finalState：处理后坐席实际所处状态（状态不变时等于 prevState）</li>
 * </ul>
 */
public final class AgentStateLua {

    private AgentStateLua() {
    }

    public static final String INGEST = """
            local hasEventId        = tonumber(ARGV[1])
            local dedupTtl          = tonumber(ARGV[2])
            local eventId           = ARGV[3]
            local eventTime         = tonumber(ARGV[4])
            local newState          = ARGV[5]
            local eventTypeName     = ARGV[6]
            local enterTimeAssumed  = tonumber(ARGV[7])
            local eventDateMidnight = tonumber(ARGV[8])
            local statsTtl          = tonumber(ARGV[9])
            local activeSetTtl      = tonumber(ARGV[10])
            local agentId           = ARGV[11]
            local callId            = ARGV[12]
            local serverNow         = tonumber(ARGV[13])
            local hasSeq            = tonumber(ARGV[14])
            local seq               = tonumber(ARGV[15]) % 4294967296   -- 归一化到 32 位（ECHI 4 字节序号语义）
            local stateTtl          = tonumber(ARGV[16])
            local maxFutureSkewMs   = tonumber(ARGV[17])

            -- 1) 事件幂等去重（SET NX 原子占位）
            if hasEventId == 1 then
              if redis.call('SET', KEYS[2], '1', 'NX', 'EX', dedupTtl) == false then
                return {1, '', 0, 0, 0, ''}
              end
            end

            -- 2) 读取当前状态并做乐观校验（防止与并发事件交叉写坏统计）
            local prevState  = redis.call('HGET', KEYS[1], 'state')
            local enterTimeS = redis.call('HGET', KEYS[1], 'stateEnterTime')
            local enterTime  = -1
            if enterTimeS then enterTime = tonumber(enterTimeS) end
            if enterTime ~= enterTimeAssumed then
              return {2, prevState or '', 0, 0, 0, ''}
            end

            -- 3) 时钟偏差防护：事件时间超前服务器时间超过阈值则拒绝
            --    （此时去重占位由调用方清除，允许调用方校对时间后重推）
            if eventTime > serverNow + maxFutureSkewMs then
              return {4, prevState or '', 0, 0, 0, ''}
            end

            -- 4) 事件序列号防护（参考 Avaya ECHI SEQUENCE_NUMBER：4 字节序号计满回绕归零）
            --    回绕感知比较：前向距离 diff = (seq - lastSeq) mod 2^32；
            --    diff ∈ (0, 2^31] 视为有效前进（含回绕），diff=0（重复）或超过半周期（过旧/序号重置）拒绝。
            --    约束：同一坐席相邻事件的前向跨度必须小于 2^31（与 ECHI 相同）。
            local lastSeqS = redis.call('HGET', KEYS[1], 'lastEventSeq')
            if hasSeq == 1 then
              if lastSeqS then
                local lastSeq = tonumber(lastSeqS)
                local diff = (seq - lastSeq) % 4294967296
                if diff == 0 or diff > 2147483648 then
                  return {3, prevState or '', 0, 0, 0, ''}
                end
                if seq < lastSeq then
                  -- 原始值回退但回绕距离合法：序号发生回绕，累计回绕次数
                  redis.call('HINCRBY', KEYS[1], 'seqWrapCount', 1)
                end
              else
                redis.call('HSET', KEYS[1], 'seqWrapCount', 0)
              end
            end

            -- 5) 状态机裁决（参考 Avaya：迟到事件只记录不改变状态机，同状态转移不切分时长）
            local outOfOrder   = 0
            local sameState    = 0
            local stateChanged = 1
            if enterTime >= 0 and eventTime < enterTime then
              -- 乱序事件：仅更新观测字段，不闭合窗口、不转移状态、不重置进入时间
              outOfOrder = 1
              stateChanged = 0
            elseif prevState == newState and prevState ~= false and prevState ~= '' then
              -- 同状态转移：不切分窗口、进入时间不变、通话量不重复计数
              sameState = 1
              stateChanged = 0
            end

            -- 6) 窗口闭合与新状态写入（仅状态真正转移时执行）
            local stateDurationField = {
              IDLE     = 'idleDurationMs',
              IN_CALL  = 'callDurationMs',
              WRAP_UP  = 'wrapUpDurationMs',
              BREAK    = 'breakDurationMs',
              OFFLINE  = 'offlineDurationMs'
            }
            local closedMs = 0
            if stateChanged == 1 then
              if enterTime >= 0 and eventTime >= enterTime then
                closedMs = eventTime - enterTime
                if closedMs > 0 then
                  local field = stateDurationField[prevState] or 'idleDurationMs'
                  if enterTime >= eventDateMidnight then
                    -- 窗口与事件同一天
                    redis.call('HINCRBY', KEYS[3], field, closedMs)
                    redis.call('EXPIRE', KEYS[3], statsTtl)
                  else
                    -- 跨天窗口：零点前计入窗口起始日，零点后计入事件日
                    -- （窗口跨多天时，中间时段一并归入起始日，保证总量不丢）
                    redis.call('HINCRBY', KEYS[4], field, eventDateMidnight - enterTime)
                    redis.call('EXPIRE', KEYS[4], statsTtl)
                    redis.call('HINCRBY', KEYS[3], field, eventTime - eventDateMidnight)
                    redis.call('EXPIRE', KEYS[3], statsTtl)
                  end
                end
              end
              redis.call('HSET', KEYS[1], 'state', newState, 'stateEnterTime', eventTime)
              redis.call('HDEL', KEYS[1], 'currentCallId')
              -- 通话开始：仅真正从非通话态进入通话态时通话量 +1，记录当前通话标识
              if newState == 'IN_CALL' then
                redis.call('HINCRBY', KEYS[3], 'callCount', 1)
                redis.call('EXPIRE', KEYS[3], statsTtl)
                if callId ~= '' then
                  redis.call('HSET', KEYS[1], 'currentCallId', callId)
                end
              end
            end

            -- 7) 观测字段与状态 TTL（所有接受路径都刷新，防止状态 hash 变僵尸）
            redis.call('HSET', KEYS[1],
              'lastEventType', eventTypeName,
              'lastEventTime', eventTime,
              'updatedAt', serverNow)
            if hasSeq == 1 then
              redis.call('HSET', KEYS[1], 'lastEventSeq', seq)
            end
            redis.call('EXPIRE', KEYS[1], stateTtl)

            -- 8) 坐席登记（团队汇总用）
            redis.call('SADD', KEYS[6], agentId)
            redis.call('SADD', KEYS[5], agentId)
            redis.call('EXPIRE', KEYS[5], activeSetTtl)

            local finalState = stateChanged == 1 and newState or (prevState or '')
            return {0, prevState or '', closedMs, outOfOrder, sameState, finalState}
            """;
}
