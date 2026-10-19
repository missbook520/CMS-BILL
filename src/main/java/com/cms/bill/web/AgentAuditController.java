package com.cms.bill.web;

import com.cms.bill.audit.EventAuditDisposition;
import com.cms.bill.audit.EventAuditRepository;
import com.cms.bill.audit.EventAuditService;
import com.cms.bill.common.ApiResponse;
import com.cms.bill.common.BizException;
import com.cms.bill.common.FlexibleInstantDeserializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 事件审计查询接口（对账依据：一切事件含重复均落库留痕）。
 */
@RestController
@RequestMapping("/api/v1/agent-events")
public class AgentAuditController {

    private final EventAuditService auditService;

    public AgentAuditController(EventAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 分页查询事件审计记录。
     *
     * @param agentId     坐席工号（可选）
     * @param from        起始事件时间（可选，格式同 eventTime 上报格式）
     * @param to          截止事件时间（可选，格式同上）
     * @param disposition 处置结果（可选，RECEIVED/ACCEPTED/DUPLICATE/IGNORED/REJECTED）
     * @param page        页码（从 0 开始）
     * @param size        每页条数（1~200）
     */
    @GetMapping("/audit")
    public ApiResponse<EventAuditRepository.AuditPage> audit(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String disposition,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (disposition != null && !disposition.isBlank()) {
            try {
                EventAuditDisposition.valueOf(disposition.trim());
            } catch (IllegalArgumentException e) {
                throw new BizException(400, "无法识别的 disposition: " + disposition
                        + "，合法值: RECEIVED/ACCEPTED/DUPLICATE/IGNORED/REJECTED");
            }
        }
        return ApiResponse.ok(auditService.find(agentId, parseTime(from, "from"), parseTime(to, "to"),
                disposition, page, size));
    }

    /** 按处置结果分组计数（对账汇总）。 */
    @GetMapping("/audit/summary")
    public ApiResponse<Map<String, Long>> summary(@RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        return ApiResponse.ok(auditService.summarize(parseTime(from, "from"), parseTime(to, "to")));
    }

    /** 时间参数解析：纯数字按 epoch（≤11 位按秒，否则毫秒），其余走宽松解析。 */
    private static Long parseTime(String text, String name) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            if (text.trim().matches("\\d+")) {
                long v = Long.parseLong(text.trim());
                return v <= 99_999_999_999L ? v * 1000 : v;
            }
            return FlexibleInstantDeserializer.parseFlexible(text).toEpochMilli();
        } catch (Exception e) {
            throw new BizException(400, "无法解析时间参数 " + name + ": \"" + text + "\"，"
                    + "支持 epoch（秒/毫秒）、ISO-8601(含时区) 或 yyyy-MM-dd HH:mm:ss");
        }
    }
}
