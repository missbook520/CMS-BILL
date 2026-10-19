package com.cms.bill.service;

import com.cms.bill.audit.EventAuditDisposition;
import com.cms.bill.audit.EventAuditService;
import com.cms.bill.common.BizException;
import com.cms.bill.event.AgentEventRequest;
import com.cms.bill.event.AgentEventType;
import com.cms.bill.redis.AgentStateLua;
import com.cms.bill.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 事件接入服务。
 *
 * <p>解析并校验事件，调用 Lua 脚本原子完成：事件去重、乐观并发校验、
 * 上一状态窗口闭合（跨天拆分）、统计累加与新状态写入。
 * 整个状态转移在 Redis 端串行执行，无需分布式锁。</p>
 *
 * <p>可靠性设计（参考 Avaya CMS/ECHI）：</p>
 * <ul>
 *   <li>事件ID幂等去重：重复事件直接确认，不产生任何统计变更</li>
 *   <li>事件序列号防护：携带 seq 时按 32 位回绕语义校验单调递增（对应 ECHI SEQUENCE_NUMBER
 *       4 字节序号计满回绕归零），回绕后继续接受并累计 seqWrapCount；重复/过旧事件返回
 *       200 ignored（去重占位保留，对应 ECHI 重传重复识别）</li>
 *   <li>时钟偏差防护：事件时间超前服务器时间超过阈值返回 400
 *       （清除去重占位，允许调用方校对后重推）</li>
 *   <li>乱序事件：不倒退状态机，仅记录观测字段并标记 outOfOrder</li>
 *   <li>同状态事件：不切分时长窗口、不重复计数，标记 stateUnchanged</li>
 * </ul>
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    /** 乐观并发冲突的最大重试次数 */
    private static final int MAX_RETRY = 3;

    private static final DefaultRedisScript<List> INGEST_SCRIPT =
            new DefaultRedisScript<>(AgentStateLua.INGEST, List.class);

    private final StringRedisTemplate redis;
    private final EventAuditService auditService;
    private final long statsTtlSeconds;
    private final long dedupTtlSeconds;
    private final long stateTtlSeconds;
    private final long maxFutureSkewMs;
    private final long activeSetTtlSeconds = 2L * 24 * 3600;

    public EventIngestService(StringRedisTemplate redis,
                              EventAuditService auditService,
                              @Value("${app.stats.retention-days:30}") long retentionDays,
                              @Value("${app.dedup.ttl-seconds:86400}") long dedupTtlSeconds,
                              @Value("${app.state.ttl-seconds:604800}") long stateTtlSeconds,
                              @Value("${app.ingest.max-future-skew-seconds:300}") long maxFutureSkewSeconds) {
        this.redis = redis;
        this.auditService = auditService;
        this.statsTtlSeconds = retentionDays * 24 * 3600;
        this.dedupTtlSeconds = dedupTtlSeconds;
        this.stateTtlSeconds = stateTtlSeconds;
        this.maxFutureSkewMs = maxFutureSkewSeconds * 1000;
    }

    /**
     * 处理单条坐席事件（含审计旁路：处理前先落 RECEIVED 行，处理后回填处置结果；
     * 审计读写失败仅记日志，不阻断主流程）。
     *
     * @throws BizException 400=事件类型无法识别 / 事件时间超前过多；409=并发冲突重试耗尽
     */
    public IngestResult ingest(AgentEventRequest req) {
        long auditId = auditService.recordReceived(req);
        try {
            IngestResult result = doIngest(req);
            EventAuditDisposition disposition = result.duplicate() ? EventAuditDisposition.DUPLICATE
                    : result.ignored() ? EventAuditDisposition.IGNORED
                    : EventAuditDisposition.ACCEPTED;
            auditService.recordOutcome(auditId, disposition, result.ignoredReason(), result);
            return result;
        } catch (BizException e) {
            auditService.recordOutcome(auditId, EventAuditDisposition.REJECTED, e.getMessage(), null);
            throw e;
        }
    }

    private IngestResult doIngest(AgentEventRequest req) {
        AgentEventType type = AgentEventType.parse(req.eventType());
        if (type == null) {
            throw new BizException(400,
                    "无法识别的 eventType: " + req.eventType() + "，合法值: " + AgentEventType.validValues());
        }

        String agentId = req.agentId().trim();
        boolean hasEventId = req.eventId() != null && !req.eventId().isBlank();
        String eventId = hasEventId ? req.eventId().trim() : "";
        // 无 eventId 时使用占位 key（脚本内 hasEventId=0 不会触碰）
        String dedupKey = RedisKeys.eventDedup(agentId, hasEventId ? eventId : "__none__");
        String callId = req.callId() == null ? "" : req.callId().trim();
        long eventTimeMs = req.eventTime().toEpochMilli();
        boolean hasSeq = req.seq() != null;
        long seq = hasSeq ? req.seq() : 0;

        ZoneId zone = ZoneId.systemDefault();
        LocalDate eventDate = req.eventTime().atZone(zone).toLocalDate();
        long eventDateMidnight = eventDate.atStartOfDay(zone).toInstant().toEpochMilli();
        String eventDateStatsKey = RedisKeys.agentStats(agentId, eventDate);
        String stateKey = RedisKeys.agentState(agentId);

        // 乐观重试：读状态快照 -> Lua 校验 -> 快照过期则重读重试
        for (int attempt = 1; ; attempt++) {
            Object enterTimeObj = redis.opsForHash().get(stateKey, "stateEnterTime");
            long enterTimeAssumed = enterTimeObj == null ? -1 : Long.parseLong(enterTimeObj.toString());
            String enterDateStatsKey = enterTimeAssumed >= 0
                    ? RedisKeys.agentStats(agentId, Instant.ofEpochMilli(enterTimeAssumed).atZone(zone).toLocalDate())
                    : eventDateStatsKey;

            List<Object> r = redis.execute(INGEST_SCRIPT,
                    List.of(stateKey, dedupKey, eventDateStatsKey, enterDateStatsKey,
                            RedisKeys.activeAgents(eventDate), RedisKeys.allAgents()),
                    String.valueOf(hasEventId ? 1 : 0),
                    String.valueOf(dedupTtlSeconds),
                    eventId,
                    String.valueOf(eventTimeMs),
                    type.getTargetState().name(),
                    type.name(),
                    String.valueOf(enterTimeAssumed),
                    String.valueOf(eventDateMidnight),
                    String.valueOf(statsTtlSeconds),
                    String.valueOf(activeSetTtlSeconds),
                    agentId,
                    callId,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(hasSeq ? 1 : 0),
                    String.valueOf(seq),
                    String.valueOf(stateTtlSeconds),
                    String.valueOf(maxFutureSkewMs));

            long code = (Long) r.get(0);
            if (code == 0) {
                if (attempt > 1) {
                    log.info("坐席[{}] 事件[{}] 第{}次乐观重试成功", agentId, eventId, attempt);
                }
                String prevState = (String) r.get(1);
                long closedMs = (Long) r.get(2);
                boolean outOfOrder = ((Long) r.get(3)) == 1;
                boolean sameState = ((Long) r.get(4)) == 1;
                String finalState = (String) r.get(5);
                boolean stateUnchanged = outOfOrder || sameState;
                if (outOfOrder) {
                    log.warn("坐席[{}] 收到乱序事件[{}](type={}, time={})，状态机不倒退，仅记录",
                            agentId, eventId, type, req.eventTime());
                }
                return new IngestResult(agentId, eventId, type.name(), type.getLabel(), req.eventTime(),
                        false, false, null, outOfOrder, stateUnchanged,
                        prevState.isEmpty() ? null : prevState, finalState, closedMs);
            }
            if (code == 1) {
                // 幂等命中：重复事件直接确认，未产生任何统计变更
                return new IngestResult(agentId, eventId, type.name(), type.getLabel(), req.eventTime(),
                        true, false, null, false, false, null,
                        type.getTargetState().name(), 0);
            }
            if (code == 3) {
                // 序列号过旧（参考 Avaya ECHI）：事件被忽略但确认已收到，去重占位保留
                String prevState = (String) r.get(1);
                log.warn("坐席[{}] 事件[{}] 序列号 {} 过旧或回绕距离非法（重复/序号重置），忽略该事件",
                        agentId, eventId, seq);
                return new IngestResult(agentId, eventId, type.name(), type.getLabel(), req.eventTime(),
                        false, true, "SEQ_TOO_OLD", false, true,
                        prevState.isEmpty() ? null : prevState, prevState, 0);
            }
            if (code == 4) {
                // 事件时间超前过多（时钟偏差防护）：清除去重占位，允许调用方校对后重推
                redis.delete(dedupKey);
                throw new BizException(400, "事件时间 " + req.eventTime()
                        + " 超前服务器时间超过 " + (maxFutureSkewMs / 1000) + " 秒，疑似时钟偏差，请校对后重推");
            }
            // code == 2：状态快照过期（并发事件先写入）
            if (attempt >= MAX_RETRY) {
                // 清除去重占位，允许调用方稍后重推同一事件
                redis.delete(dedupKey);
                throw new BizException(409, "坐席[" + agentId + "] 事件并发冲突，重试耗尽，请稍后重推");
            }
            log.debug("坐席[{}] 事件[{}] 状态快照过期，进行第{}次重试", agentId, eventId, attempt + 1);
        }
    }

    /**
     * 单条事件处理结果。
     *
     * @param duplicate      是否为重复事件（事件ID幂等命中）
     * @param ignored        是否被忽略（如序列号过旧），事件已被确认但不产生统计变更
     * @param ignoredReason  忽略原因（SEQ_TOO_OLD），未忽略为 null
     * @param outOfOrder     是否乱序（事件时间早于当前状态进入时间，状态机不倒退，仅记录）
     * @param stateUnchanged 状态是否未发生变化（乱序或同状态转移）
     * @param prevState      事件前状态（首次上报为 null）
     * @param newState       事件后坐席实际所处状态（状态未变时等于 prevState）
     * @param closedWindowMs 本次闭合的上一状态窗口时长（毫秒）
     */
    public record IngestResult(
            String agentId,
            String eventId,
            String eventType,
            String eventTypeLabel,
            Instant eventTime,
            boolean duplicate,
            boolean ignored,
            String ignoredReason,
            boolean outOfOrder,
            boolean stateUnchanged,
            String prevState,
            String newState,
            long closedWindowMs) {
    }
}
