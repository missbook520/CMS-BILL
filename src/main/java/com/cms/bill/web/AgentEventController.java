package com.cms.bill.web;

import com.cms.bill.common.ApiResponse;
import com.cms.bill.common.BizException;
import com.cms.bill.event.AgentEventRequest;
import com.cms.bill.service.EventIngestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 坐席事件推送接口。
 */
@RestController
@RequestMapping("/api/v1/agent-events")
public class AgentEventController {

    private final EventIngestService ingestService;

    public AgentEventController(EventIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /** 单条事件推送 */
    @PostMapping
    public ApiResponse<EventIngestService.IngestResult> push(@Valid @RequestBody AgentEventRequest req) {
        return ApiResponse.ok(ingestService.ingest(req));
    }

    /**
     * 批量事件推送：按事件时间升序逐条处理（保证窗口闭合顺序正确），
     * 单条失败不影响其余事件，逐条返回处理结果。
     */
    @PostMapping("/batch")
    public ApiResponse<List<BatchItem>> batch(@Valid @RequestBody AgentEventBatchRequest body) {
        List<AgentEventRequest> sorted = body.events().stream()
                .sorted(Comparator.comparing(AgentEventRequest::eventTime))
                .toList();
        List<BatchItem> items = new ArrayList<>(sorted.size());
        int index = 0;
        for (AgentEventRequest req : sorted) {
            try {
                items.add(new BatchItem(index, req.agentId(), true, null, ingestService.ingest(req)));
            } catch (BizException e) {
                items.add(new BatchItem(index, req.agentId(), false, e.getMessage(), null));
            }
            index++;
        }
        return ApiResponse.ok(items);
    }

    /** 批量推送请求体 */
    public record AgentEventBatchRequest(@NotEmpty(message = "events 不能为空") @Valid List<AgentEventRequest> events) {
    }

    /** 批量推送逐条结果 */
    public record BatchItem(int index, String agentId, boolean success, String error,
                            EventIngestService.IngestResult result) {
    }
}
