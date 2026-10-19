package com.cms.bill.event;

import com.cms.bill.common.FlexibleInstantDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * 坐席事件推送请求体。
 *
 * @param agentId   坐席工号
 * @param eventId   事件唯一ID（可选；提供后按坐席+事件ID做幂等去重）
 * @param eventType 事件类型（支持枚举名或中文别名，如 CALL_START / 通话开始）
 * @param eventTime 事件发生时间（支持 epoch 毫秒 / ISO-8601 / yyyy-MM-dd HH:mm:ss）
 * @param seq       事件序列号（可选；32 位序号语义，计满回绕归零，按回绕感知距离校验
 *                  单调递增，重复/过旧事件被忽略，参考 Avaya ECHI SEQUENCE_NUMBER 防重放机制）
 * @param callId    通话唯一标识（通话开始/结束时可选提供）
 * @param ext       透传扩展字段（不参与统计，仅记录）
 */
public record AgentEventRequest(
        @NotBlank(message = "agentId 不能为空") String agentId,
        String eventId,
        @NotBlank(message = "eventType 不能为空") String eventType,
        @NotNull(message = "eventTime 不能为空")
        @JsonDeserialize(using = FlexibleInstantDeserializer.class) Instant eventTime,
        Long seq,
        String callId,
        Map<String, Object> ext) {
}
