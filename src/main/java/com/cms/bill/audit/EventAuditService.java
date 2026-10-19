package com.cms.bill.audit;

import com.cms.bill.event.AgentEventRequest;
import com.cms.bill.service.EventIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 事件审计服务：处理前先落 RECEIVED 行，处理完成后回填处置结果。
 *
 * <p>审计为旁路 best-effort：任何读写失败仅记日志返回哨兵值（-1），不阻断事件主流程；
 * Redis 去重仍是热路径，DB 审计仅作为对账与重放依据。</p>
 *
 * <p>保留策略：定时清理超过 {@code app.audit.retention-days}（默认 31 天）的记录，
 * 对齐 CMS 区间数据的保留期。</p>
 */
@Service
public class EventAuditService {

    private static final Logger log = LoggerFactory.getLogger(EventAuditService.class);

    private final EventAuditRepository repo;
    private final ObjectMapper objectMapper;
    private final long retentionDays;

    public EventAuditService(EventAuditRepository repo,
                             ObjectMapper objectMapper,
                             @Value("${app.audit.retention-days:31}") long retentionDays) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.retentionDays = retentionDays;
    }

    /**
     * 处理前先行落库 RECEIVED 行。
     *
     * @return 审计主键；落库失败返回 -1（不阻断主流程）
     */
    public long recordReceived(AgentEventRequest req) {
        try {
            String payload = objectMapper.writeValueAsString(req);
            return repo.insertReceived(
                    blankToNull(req.eventId()),
                    req.agentId() == null ? null : req.agentId().trim(),
                    req.eventType(),
                    req.eventTime() == null ? System.currentTimeMillis() : req.eventTime().toEpochMilli(),
                    req.seq(),
                    blankToNull(req.callId()),
                    payload,
                    System.currentTimeMillis());
        } catch (Exception e) {
            log.error("事件审计落库失败（不影响主流程）: agentId={}, eventId={}", req.agentId(), req.eventId(), e);
            return -1;
        }
    }

    /**
     * 处理完成后回填处置结果；auditId=-1（落库失败）时直接跳过。
     *
     * @param result    事件处理结果；REJECTED（校验失败）场景为 null
     * @param detail    处置补充说明（如忽略原因、拒绝原因），超长截断
     */
    public void recordOutcome(long auditId, EventAuditDisposition disposition, String detail,
                              EventIngestService.IngestResult result) {
        if (auditId < 0) {
            return;
        }
        try {
            String prevState = result == null ? null : blankToNull(result.prevState());
            String newState = result == null ? null : blankToNull(result.newState());
            repo.recordOutcome(auditId, disposition.name(), detail, prevState, newState,
                    result == null ? null : result.closedWindowMs(),
                    result != null && result.outOfOrder(),
                    result != null && result.stateUnchanged());
        } catch (Exception e) {
            log.error("事件审计回填失败（不影响主流程）: auditId={}, disposition={}", auditId, disposition, e);
        }
    }

    /** 分页查询审计记录（page 从 0 开始，size 限制在 1~200）。 */
    public EventAuditRepository.AuditPage find(String agentId, Long fromMs, Long toMs,
                                               String disposition, int page, int size) {
        return repo.find(blankToNull(agentId), fromMs, toMs, blankToNull(disposition),
                Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    /** 按处置结果分组计数。 */
    public Map<String, Long> summarize(Long fromMs, Long toMs) {
        return repo.summarize(fromMs, toMs);
    }

    /** 定时清理超过保留期的审计记录（默认每天 03:30）。 */
    @Scheduled(cron = "${app.audit.purge-cron:0 30 3 * * *}")
    public void purgeExpired() {
        try {
            long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600 * 1000;
            int deleted = repo.purgeBefore(cutoff);
            if (deleted > 0) {
                log.info("事件审计清理完成：删除 {} 条 {} 天前的记录", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.error("事件审计清理失败", e);
        }
    }

    private static String blankToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
