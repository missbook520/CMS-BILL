package com.cms.bill.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件审计仓储测试（内嵌 H2）。
 */
@JdbcTest
@Sql("classpath:schema.sql")
class EventAuditRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private EventAuditRepository repo;

    @BeforeEach
    void setUp() {
        repo = new EventAuditRepository(jdbc);
    }

    @Test
    void insertReceivedThenOutcomeThenFind() {
        long id = repo.insertReceived("EV-1001", "8001", "CALL_START", 1760000000000L,
                42L, "CALL-9", "{\"agentId\":\"8001\",\"seq\":42}", 1760000001000L);
        assertTrue(id > 0);

        // 处理前先行落库可见（崩溃对账依据）
        EventAuditRepository.AuditPage received = repo.find("8001", null, null, "RECEIVED", 0, 20);
        assertEquals(1, received.total());
        assertEquals("RECEIVED", received.content().get(0).disposition());

        assertEquals(1, repo.recordOutcome(id, "ACCEPTED", null, "OFFLINE", "IN_CALL", 3500L, false, false));

        EventAuditRepository.AuditPage page = repo.find("8001", null, null, "ACCEPTED", 0, 20);
        assertEquals(1, page.total());
        EventAuditRecord r = page.content().get(0);
        assertEquals(id, r.id());
        assertEquals("EV-1001", r.eventId());
        assertEquals("8001", r.agentId());
        assertEquals("CALL_START", r.eventType());
        assertEquals(1760000000000L, r.eventTimeMs());
        assertEquals(42L, r.seq());
        assertEquals("CALL-9", r.callId());
        assertEquals("ACCEPTED", r.disposition());
        assertEquals("OFFLINE", r.prevState());
        assertEquals("IN_CALL", r.newState());
        assertEquals(3500L, r.closedWindowMs());
        assertFalse(r.outOfOrder());
        assertFalse(r.stateUnchanged());
        assertEquals(1760000001000L, r.receivedAtMs());
        assertTrue(r.payload().contains("\"agentId\":\"8001\""));
    }

    @Test
    void filterAndSummary() {
        long id1 = repo.insertReceived("EV-A", "8002", "IDLE", 1760000000000L, null, null, "{}", 1760000001000L);
        long id2 = repo.insertReceived("EV-B", "8002", "CALL_START", 1760000002000L, null, null, "{}", 1760000003000L);
        long id3 = repo.insertReceived("EV-C", "8003", "CALL_END", 1760000004000L, null, null, "{}", 1760000005000L);
        repo.recordOutcome(id1, "IGNORED", "SEQ_TOO_OLD", null, null, null, false, true);
        repo.recordOutcome(id2, "REJECTED", "无法识别的 eventType", null, null, null, false, false);
        repo.recordOutcome(id3, "ACCEPTED", null, null, null, null, true, false);

        assertEquals(2, repo.find("8002", null, null, null, 0, 20).total());
        assertEquals(1, repo.find("8002", null, null, "IGNORED", 0, 20).total());
        assertEquals(1, repo.find("8002", null, null, "REJECTED", 0, 20).total());
        assertEquals(0, repo.find("8002", null, null, "DUPLICATE", 0, 20).total());

        EventAuditRecord ignored = repo.find("8002", null, null, "IGNORED", 0, 20).content().get(0);
        assertEquals("SEQ_TOO_OLD", ignored.detail());
        assertTrue(ignored.stateUnchanged());
        EventAuditRecord accepted = repo.find("8003", null, null, "ACCEPTED", 0, 20).content().get(0);
        assertTrue(accepted.outOfOrder());

        Map<String, Long> summary = repo.summarize(null, null);
        assertEquals(1L, summary.get("IGNORED"));
        assertEquals(1L, summary.get("REJECTED"));
        assertEquals(1L, summary.get("ACCEPTED"));

        // 事件时间范围过滤
        assertEquals(2, repo.find(null, 1760000000000L, 1760000002000L, null, 0, 20).total());
    }

    @Test
    void purgeBefore() {
        long now = System.currentTimeMillis();
        repo.insertReceived("EV-OLD", "8004", "IDLE", now - 40L * 86400_000, null, null, "{}",
                now - 40L * 86400_000);
        repo.insertReceived("EV-NEW", "8004", "IDLE", now, null, null, "{}", now);

        assertEquals(1, repo.purgeBefore(now - 31L * 86400_000));
        assertEquals(0, repo.purgeBefore(now - 31L * 86400_000));
        assertEquals(1, repo.find("8004", null, null, null, 0, 20).total());
    }
}
