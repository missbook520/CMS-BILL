package com.cms.bill.redis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis key 统一命名。
 */
public final class RedisKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private RedisKeys() {
    }

    /** 坐席当前状态 hash */
    public static String agentState(String agentId) {
        return "cmsbill:agent:state:" + agentId;
    }

    /** 坐席某天的指标统计 hash */
    public static String agentStats(String agentId, LocalDate day) {
        return "cmsbill:agent:stats:" + agentId + ":" + DAY.format(day);
    }

    /** 事件幂等去重 key（按坐席+事件ID） */
    public static String eventDedup(String agentId, String eventId) {
        return "cmsbill:event:dedup:" + agentId + ":" + eventId;
    }

    /** 某天有事件上报的坐席集合（用于团队汇总） */
    public static String activeAgents(LocalDate day) {
        return "cmsbill:agents:active:" + DAY.format(day);
    }

    /** 全量坐席工号集合 */
    public static String allAgents() {
        return "cmsbill:agents:all";
    }
}
