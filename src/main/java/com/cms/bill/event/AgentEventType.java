package com.cms.bill.event;

import java.util.Set;

/**
 * 坐席事件类型。
 * 每类事件映射到事件发生后坐席进入的目标状态。
 *
 * <p>窗口闭合策略：部分状态没有显式结束事件（如整理态、休息），
 * 由「下一个任意事件」按其时间戳闭合上一状态窗口，保证时长统计不丢不重。</p>
 */
public enum AgentEventType {

    LOGIN("签入", AgentState.IDLE,
            Set.of("login", "登录", "上线")),
    LOGOUT("签出", AgentState.OFFLINE,
            Set.of("logout", "登出", "下线")),
    READY("示闲", AgentState.IDLE,
            Set.of("ready", "idle", "示闲", "空闲", "就绪")),

    CALL_START("通话开始", AgentState.IN_CALL,
            Set.of("call_start", "callstart", "talking", "通话开始", "呼入开始", "呼出开始", "接听")),
    CALL_END("通话结束", AgentState.IDLE,
            Set.of("call_end", "callend", "hangup", "通话结束", "挂机")),

    WRAP_UP_START("整理态开始", AgentState.WRAP_UP,
            Set.of("wrap_up_start", "wrapupstart", "acw", "整理态开始", "话后整理开始", "整理开始")),
    WRAP_UP_END("整理态结束", AgentState.IDLE,
            Set.of("wrap_up_end", "wrapupend", "整理态结束", "话后整理结束")),

    BREAK_START("休息开始", AgentState.BREAK,
            Set.of("break_start", "breakstart", "rest_start", "休息开始", "小休开始")),
    BREAK_END("休息结束", AgentState.IDLE,
            Set.of("break_end", "breakend", "rest_end", "休息结束", "小休结束"));

    private final String label;
    private final AgentState targetState;
    private final Set<String> aliases;

    AgentEventType(String label, AgentState targetState, Set<String> aliases) {
        this.label = label;
        this.targetState = targetState;
        this.aliases = aliases;
    }

    public String getLabel() {
        return label;
    }

    /** 事件发生后坐席进入的状态 */
    public AgentState getTargetState() {
        return targetState;
    }

    /**
     * 按事件类型名或别名解析，忽略大小写。
     *
     * @return 匹配的事件类型；无法识别返回 null
     */
    public static AgentEventType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        for (AgentEventType t : values()) {
            if (t.name().equalsIgnoreCase(s)) {
                return t;
            }
            for (String alias : t.aliases) {
                if (alias.equalsIgnoreCase(s)) {
                    return t;
                }
            }
        }
        return null;
    }

    public static String validValues() {
        StringBuilder sb = new StringBuilder();
        for (AgentEventType t : values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(t.name());
        }
        return sb.toString();
    }
}
