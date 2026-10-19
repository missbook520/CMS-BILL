package com.cms.bill.service;

import com.cms.bill.audit.EventAuditRecord;
import com.cms.bill.audit.EventAuditRepository;
import com.cms.bill.audit.EventAuditService;
import com.cms.bill.common.BizException;
import com.cms.bill.event.AgentEventRequest;
import com.cms.bill.redis.RedisKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * seq 回绕 + 事件审计落库集成测试（真实 Redis + 内存 H2）。
 *
 * <p>覆盖：ECHI 4 字节序号回绕接受与 seqWrapCount 累计、重复/回退/超半周期跨度拒绝、
 * 超大序号归一化、审计处置（ACCEPTED/DUPLICATE/IGNORED/REJECTED）落库。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:h2:mem:ingestit;DB_CLOSE_DELAY=-1")
@EnabledIf("redisAvailable")
class EventIngestWraparoundIntegrationTest {

    @Autowired
    private EventIngestService ingestService;

    @Autowired
    private EventAuditService auditService;

    @Autowired
    private StringRedisTemplate redis;

    static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static AgentEventRequest event(String agentId, String eventId, String eventType,
                                           Long seq, Instant eventTime) {
        return new AgentEventRequest(agentId, eventId, eventType, eventTime, seq, null, null);
    }

    private static String uniqueAgent() {
        return "ITW" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void sequenceWraparoundAcceptedAndCounted() {
        String agentId = uniqueAgent();
        Instant base = Instant.now();
        // 4294967290 -> 4294967295 -> 3（回绕归零后继续前进）
        assertEquals("IDLE", ingestService
                .ingest(event(agentId, "W1", "IDLE", 4294967290L, base)).newState());
        assertEquals("IN_CALL", ingestService
                .ingest(event(agentId, "W2", "CALL_START", 4294967295L, base.plusMillis(1))).newState());
        var third = ingestService.ingest(event(agentId, "W3", "CALL_END", 3L, base.plusMillis(2)));
        assertFalse(third.duplicate());
        assertFalse(third.ignored());
        assertEquals("IDLE", third.newState());

        Map<Object, Object> state = redis.opsForHash().entries(RedisKeys.agentState(agentId));
        assertEquals("3", state.get("lastEventSeq"));
        assertEquals("1", state.get("seqWrapCount"));
    }

    @Test
    void duplicateRollbackAndOverHalfCycleRejectedButOversizedNormalized() {
        String agentId = uniqueAgent();
        Instant base = Instant.now();
        assertEquals("IDLE", ingestService.ingest(event(agentId, "B1", "IDLE", 105L, base)).newState());

        // 重复序号（diff=0）
        assertTrue(ingestService.ingest(event(agentId, "B2", "CALL_START", 105L, base.plusMillis(1))).ignored());
        // 序号回退（diff 接近整周期）
        assertTrue(ingestService.ingest(event(agentId, "B3", "CALL_START", 104L, base.plusMillis(2))).ignored());
        // 前向跨度超过半周期 2^31（疑似序号重置）
        assertTrue(ingestService.ingest(event(agentId, "B4", "CALL_START",
                105L + 2147483648L + 1, base.plusMillis(3))).ignored());

        // 超大序号归一化：2^32+106 -> 106，视为有效前进
        var normalized = ingestService.ingest(event(agentId, "B5", "CALL_START",
                4294967296L + 106, base.plusMillis(4)));
        assertFalse(normalized.ignored());
        assertEquals("IN_CALL", normalized.newState());
        assertEquals("106", redis.opsForHash().get(RedisKeys.agentState(agentId), "lastEventSeq"));
    }

    @Test
    void auditDispositionsRecorded() {
        String agentId = uniqueAgent();
        Instant base = Instant.now();
        ingestService.ingest(event(agentId, "C1", "IDLE", 10L, base));
        // 同事件ID重推 -> DUPLICATE
        ingestService.ingest(event(agentId, "C1", "IDLE", 11L, base.plusMillis(1)));
        // 序号过旧 -> IGNORED
        ingestService.ingest(event(agentId, "C3", "CALL_START", 9L, base.plusMillis(2)));
        // 非法事件类型 -> REJECTED
        assertThrows(BizException.class, () -> ingestService.ingest(
                event(agentId, "C4", "BOGUS_TYPE", 12L, base.plusMillis(3))));

        EventAuditRepository.AuditPage page = auditService.find(agentId, null, null, null, 0, 50);
        assertEquals(4, page.total());
        Map<String, Long> summary = auditService.summarize(null, null);
        assertTrue(summary.containsKey("ACCEPTED"));
        assertTrue(summary.containsKey("DUPLICATE"));
        assertTrue(summary.containsKey("IGNORED"));
        assertTrue(summary.containsKey("REJECTED"));

        for (EventAuditRecord r : page.content()) {
            switch (r.disposition()) {
                case "ACCEPTED" -> {
                    assertEquals("C1", r.eventId());
                    assertTrue(r.payload().contains(agentId));
                }
                case "DUPLICATE" -> assertEquals("C1", r.eventId());
                case "IGNORED" -> assertEquals("SEQ_TOO_OLD", r.detail());
                case "REJECTED" -> assertTrue(r.detail().contains("无法识别"));
                default -> throw new IllegalStateException("意外处置: " + r.disposition());
            }
        }
    }

    @Test
    void futureEventRejectedAndAudited() {
        String agentId = uniqueAgent();
        Instant future = Instant.now().plusSeconds(3600);
        BizException e = assertThrows(BizException.class, () -> ingestService.ingest(
                event(agentId, "D1", "IDLE", null, future)));
        assertTrue(e.getMessage().contains("超前"));

        EventAuditRepository.AuditPage page = auditService.find(agentId, null, null, "REJECTED", 0, 10);
        assertEquals(1, page.total());
        EventAuditRecord rejected = page.content().get(0);
        assertTrue(rejected.detail().contains("超前"));
        assertTrue(rejected.payload().contains("D1"));
    }
}
