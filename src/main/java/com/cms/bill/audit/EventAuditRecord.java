package com.cms.bill.audit;

/**
 * 事件审计记录（对应表 agent_event_audit）。
 *
 * @param id             主键
 * @param eventId        事件唯一ID（可能为 null）
 * @param agentId        坐席工号
 * @param eventType      事件类型原始值
 * @param eventTimeMs    事件时间（epoch 毫秒）
 * @param seq            事件序列号（可能为 null）
 * @param callId         通话唯一标识（可能为 null）
 * @param disposition    处置结果（RECEIVED/ACCEPTED/DUPLICATE/IGNORED/REJECTED）
 * @param detail         处置补充说明（如忽略原因、拒绝原因）
 * @param prevState      事件前状态（可能为 null）
 * @param newState       事件后状态（可能为 null）
 * @param closedWindowMs 本次闭合的上一状态窗口时长（毫秒，可能为 null）
 * @param outOfOrder     是否乱序事件
 * @param stateUnchanged 状态是否未发生变化
 * @param payload        原始请求体 JSON
 * @param receivedAtMs   审计落库时间（epoch 毫秒）
 */
public record EventAuditRecord(
        Long id,
        String eventId,
        String agentId,
        String eventType,
        long eventTimeMs,
        Long seq,
        String callId,
        String disposition,
        String detail,
        String prevState,
        String newState,
        Long closedWindowMs,
        boolean outOfOrder,
        boolean stateUnchanged,
        String payload,
        long receivedAtMs) {
}
