package com.cms.bill.web;

import com.cms.bill.common.ApiResponse;
import com.cms.bill.common.BizException;
import com.cms.bill.service.AgentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 坐席状态与话务指标查询接口。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentQueryController {

    private final AgentQueryService queryService;

    public AgentQueryController(AgentQueryService queryService) {
        this.queryService = queryService;
    }

    /** 坐席实时状态（含今日指标快览） */
    @GetMapping("/agents/{agentId}/status")
    public ApiResponse<AgentQueryService.AgentStatus> status(@PathVariable String agentId) {
        return ApiResponse.ok(queryService.status(agentId));
    }

    /** 坐席单日话务指标（date 支持 yyyy-MM-dd / yyyyMMdd，默认今天） */
    @GetMapping("/agents/{agentId}/metrics")
    public ApiResponse<AgentQueryService.DailyMetrics> metrics(@PathVariable String agentId,
                                                               @RequestParam(required = false) String date) {
        return ApiResponse.ok(queryService.dailyMetrics(agentId, parseDate(date)));
    }

    /** 团队话务指标（date 默认今天；agentIds 逗号分隔，缺省统计当日活跃坐席） */
    @GetMapping("/team/metrics")
    public ApiResponse<AgentQueryService.TeamMetrics> teamMetrics(@RequestParam(required = false) String date,
                                                                  @RequestParam(required = false) String agentIds) {
        List<String> ids = agentIds == null || agentIds.isBlank()
                ? List.of()
                : Arrays.stream(agentIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ApiResponse.ok(queryService.teamMetrics(parseDate(date), ids));
    }

    /** 全量坐席实时状态列表 */
    @GetMapping("/agents")
    public ApiResponse<List<AgentQueryService.AgentBrief>> agents() {
        return ApiResponse.ok(queryService.listAgents());
    }

    /** 日期参数解析：yyyy-MM-dd / yyyyMMdd，缺省今天 */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now();
        }
        String s = raw.trim();
        try {
            if (s.length() == 8) {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new BizException(400, "非法日期参数: " + raw + "，支持 yyyy-MM-dd 或 yyyyMMdd");
        }
    }
}
