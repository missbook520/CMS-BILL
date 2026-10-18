package com.cms.bill.service;

import com.cms.bill.common.BizException;
import com.cms.bill.event.AgentState;
import com.cms.bill.redis.RedisKeys;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 坐席状态与话务指标查询服务。
 *
 * <p>查询「今天」时会把进行中的状态窗口实时补充进统计（live 标记），
 * 历史日期只读已闭合的统计值。</p>
 */
@Service
public class AgentQueryService {

    private final StringRedisTemplate redis;
    private final ZoneId zone = ZoneId.systemDefault();

    public AgentQueryService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 坐席实时状态（含今日指标快览） */
    public AgentStatus status(String agentId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.agentState(agentId));
        if (h.isEmpty()) {
            throw new BizException(404, "坐席[" + agentId + "] 不存在或尚未上报事件");
        }
        AgentState st = AgentState.valueOf((String) h.get("state"));
        long enterTime = parseLong(h.get("stateEnterTime"));
        long now = System.currentTimeMillis();
        return new AgentStatus(
                agentId,
                st.name(), st.getLabel(),
                enterTime,
                Math.max(0, now - enterTime),
                str(h.get("lastEventType")),
                parseLong(h.get("lastEventTime")),
                str(h.get("currentCallId")),
                Instant.ofEpochMilli(now),
                dailyMetrics(agentId, LocalDate.now(zone)));
    }

    /** 坐席单日话务指标 */
    public DailyMetrics dailyMetrics(String agentId, LocalDate date) {
        Map<Object, Object> stats = redis.opsForHash().entries(RedisKeys.agentStats(agentId, date));
        Map<Object, Object> state = date.equals(LocalDate.now(zone))
                ? redis.opsForHash().entries(RedisKeys.agentState(agentId))
                : Map.of();
        return buildDaily(agentId, date, stats, state);
    }

    /** 团队话务指标；agentIds 为空时统计当日活跃坐席 */
    public TeamMetrics teamMetrics(LocalDate date, Collection<String> agentIds) {
        Set<String> agents = (agentIds == null || agentIds.isEmpty())
                ? redis.opsForSet().members(RedisKeys.activeAgents(date))
                : new LinkedHashSet<>(agentIds);
        if (agents == null) {
            agents = Set.of();
        }
        if (agents.isEmpty()) {
            return new TeamMetrics(date.toString(), 0, Map.of(), 0, 0, 0, List.of());
        }

        // pipeline 一次往返拉取全部坐席的统计与状态
        List<String> list = List.copyOf(agents);
        List<Object> results = redis.executePipelined((RedisCallback<Object>) conn -> {
            StringRedisConnection c = (StringRedisConnection) conn;
            for (String a : list) {
                c.hGetAll(RedisKeys.agentStats(a, date));
                c.hGetAll(RedisKeys.agentState(a));
            }
            return null;
        });

        List<DailyMetrics> perAgent = new ArrayList<>(list.size());
        Map<String, Long> stateCounts = new TreeMap<>();
        for (int i = 0; i < list.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> stats = (Map<Object, Object>) results.get(2 * i);
            @SuppressWarnings("unchecked")
            Map<Object, Object> state = (Map<Object, Object>) results.get(2 * i + 1);
            perAgent.add(buildDaily(list.get(i), date, stats, state));
            if (!state.isEmpty()) {
                stateCounts.merge((String) state.get("state"), 1L, Long::sum);
            }
        }

        long totalCallCount = 0;
        long totalCallDurationMs = 0;
        for (DailyMetrics m : perAgent) {
            totalCallCount += m.callCount();
            totalCallDurationMs += m.durations().getOrDefault(AgentState.IN_CALL.name(), 0L);
        }
        long avg = totalCallCount > 0 ? totalCallDurationMs / totalCallCount : 0;
        return new TeamMetrics(date.toString(), perAgent.size(), stateCounts,
                totalCallCount, totalCallDurationMs, avg, perAgent);
    }

    /** 全量坐席实时状态列表 */
    public List<AgentBrief> listAgents() {
        Set<String> all = redis.opsForSet().members(RedisKeys.allAgents());
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<String> list = List.copyOf(all);
        List<Object> results = redis.executePipelined((RedisCallback<Object>) conn -> {
            StringRedisConnection c = (StringRedisConnection) conn;
            for (String a : list) {
                c.hGetAll(RedisKeys.agentState(a));
            }
            return null;
        });
        List<AgentBrief> briefs = new ArrayList<>(list.size());
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> h = (Map<Object, Object>) results.get(i);
            if (h.isEmpty()) {
                continue;
            }
            AgentState st = AgentState.valueOf((String) h.get("state"));
            long enterTime = parseLong(h.get("stateEnterTime"));
            briefs.add(new AgentBrief(list.get(i), st.name(), st.getLabel(), enterTime, Math.max(0, now - enterTime)));
        }
        briefs.sort(Comparator.comparing(AgentBrief::agentId));
        return briefs;
    }

    // ---------- 内部构建 ----------

    /**
     * 由统计 hash 与状态 hash 构建单日指标。
     * 查询日为今天且坐席有进行中窗口时，把归属今天的窗口时间实时补进统计（live=true）。
     */
    private DailyMetrics buildDaily(String agentId, LocalDate date, Map<Object, Object> stats, Map<Object, Object> state) {
        Map<String, Long> durations = new LinkedHashMap<>();
        for (AgentState st : AgentState.values()) {
            durations.put(st.name(), parseLong(stats.get(st.getDurationField())));
        }
        boolean live = false;
        if (!state.isEmpty()) {
            AgentState st = AgentState.valueOf((String) state.get("state"));
            long enterTime = parseLong(state.get("stateEnterTime"));
            long midnight = date.atStartOfDay(zone).toInstant().toEpochMilli();
            // 进行中窗口归属查询日的时间（跨天窗口从当天零点起算）
            long liveAdd = System.currentTimeMillis() - Math.max(enterTime, midnight);
            if (liveAdd > 0) {
                durations.merge(st.name(), liveAdd, Long::sum);
                live = true;
            }
        }
        long callCount = parseLong(stats.get("callCount"));
        long callDurationMs = durations.get(AgentState.IN_CALL.name());
        long avg = callCount > 0 ? callDurationMs / callCount : 0;
        return new DailyMetrics(agentId, date.toString(), live, callCount, durations, avg);
    }

    private static long parseLong(Object o) {
        if (o == null) {
            return 0;
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    // ---------- 返回模型 ----------

    /** 坐席实时状态 */
    public record AgentStatus(
            String agentId,
            String state,
            String stateLabel,
            long stateEnterTimeMs,
            long timeInStateMs,
            String lastEventType,
            long lastEventTimeMs,
            String currentCallId,
            Instant serverTime,
            DailyMetrics today) {
    }

    /** 坐席单日话务指标 */
    public record DailyMetrics(
            String agentId,
            String date,
            boolean live,
            long callCount,
            Map<String, Long> durations,
            long avgCallDurationMs) {
    }

    /** 团队话务指标 */
    public record TeamMetrics(
            String date,
            int agentCount,
            Map<String, Long> stateCounts,
            long totalCallCount,
            long totalCallDurationMs,
            long avgCallDurationMs,
            List<DailyMetrics> perAgent) {
    }

    /** 坐席实时状态简报 */
    public record AgentBrief(
            String agentId,
            String state,
            String stateLabel,
            long stateEnterTimeMs,
            long timeInStateMs) {
    }
}
