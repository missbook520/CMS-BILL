package com.cms.bill.event;

/**
 * 坐席状态枚举。
 * 每个状态对应 Redis 日统计 hash 中的时长累加字段。
 */
public enum AgentState {

    /** 空闲/示闲 */
    IDLE("空闲", "idleDurationMs"),

    /** 通话中 */
    IN_CALL("通话中", "callDurationMs"),

    /** 整理态（话后处理） */
    WRAP_UP("整理态", "wrapUpDurationMs"),

    /** 休息（小休等） */
    BREAK("休息", "breakDurationMs"),

    /** 离线（已签出） */
    OFFLINE("离线", "offlineDurationMs");

    private final String label;
    private final String durationField;

    AgentState(String label, String durationField) {
        this.label = label;
        this.durationField = durationField;
    }

    public String getLabel() {
        return label;
    }

    /** 该状态在日统计 hash 中对应的时长累加字段名 */
    public String getDurationField() {
        return durationField;
    }
}
