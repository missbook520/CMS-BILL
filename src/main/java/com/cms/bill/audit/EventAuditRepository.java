package com.cms.bill.audit;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件审计仓储（JdbcTemplate 直连，无 ORM 依赖）。
 *
 * <p>写路径：处理前 insertReceived 先落 RECEIVED 行，处理完成后 recordOutcome 回填处置结果；
 * 进程崩溃时留下 RECEIVED 行，作为对账/补推依据。</p>
 */
@Repository
public class EventAuditRepository {

    private static final RowMapper<EventAuditRecord> MAPPER = new DataClassRowMapper<>(EventAuditRecord.class);

    private final JdbcTemplate jdbc;

    public EventAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 处理前先行落库（disposition 固定 RECEIVED），返回自增主键（获取失败返回 -1）。 */
    public long insertReceived(String eventId, String agentId, String eventType, long eventTimeMs,
                               Long seq, String callId, String payload, long receivedAtMs) {
        String sql = """
                INSERT INTO agent_event_audit
                  (event_id, agent_id, event_type, event_time_ms, seq, call_id,
                   disposition, payload, received_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, eventId);
            ps.setString(i++, agentId);
            ps.setString(i++, eventType);
            ps.setLong(i++, eventTimeMs);
            setNullableLong(ps, i++, seq);
            ps.setString(i++, callId);
            ps.setString(i++, payload);
            ps.setLong(i, receivedAtMs);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    /** 处理完成后回填处置结果，返回更新行数。 */
    public int recordOutcome(long id, String disposition, String detail, String prevState,
                             String newState, Long closedWindowMs, boolean outOfOrder, boolean stateUnchanged) {
        return jdbc.update("""
                UPDATE agent_event_audit
                   SET disposition = ?, detail = ?, prev_state = ?, new_state = ?,
                       closed_window_ms = ?, out_of_order = ?, state_unchanged = ?
                 WHERE id = ?
                """, disposition, truncate(detail), prevState, newState,
                closedWindowMs, outOfOrder, stateUnchanged, id);
    }

    /** 分页查询（按 id 倒序）；条件均为可选。 */
    public AuditPage find(String agentId, Long fromMs, Long toMs, String disposition, int page, int size) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            appendCondition(where, "agent_id = ?");
            args.add(agentId);
        }
        if (fromMs != null) {
            appendCondition(where, "event_time_ms >= ?");
            args.add(fromMs);
        }
        if (toMs != null) {
            appendCondition(where, "event_time_ms <= ?");
            args.add(toMs);
        }
        if (disposition != null && !disposition.isBlank()) {
            appendCondition(where, "disposition = ?");
            args.add(disposition);
        }
        String whereSql = where.toString();

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_event_audit" + whereSql,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<EventAuditRecord> content = jdbc.query(
                "SELECT * FROM agent_event_audit" + whereSql + " ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new AuditPage(content, total == null ? 0 : total, page, size);
    }

    /** 按处置结果分组计数（时间范围可选）。 */
    public Map<String, Long> summarize(Long fromMs, Long toMs) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (fromMs != null) {
            appendCondition(where, "event_time_ms >= ?");
            args.add(fromMs);
        }
        if (toMs != null) {
            appendCondition(where, "event_time_ms <= ?");
            args.add(toMs);
        }
        return jdbc.query("SELECT disposition, COUNT(*) AS cnt FROM agent_event_audit" + where
                        + " GROUP BY disposition",
                rs -> {
                    Map<String, Long> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getLong(2));
                    }
                    return result;
                }, args.toArray());
    }

    /** 清理指定落库时间之前的审计记录，返回删除行数。 */
    public int purgeBefore(long receivedAtMs) {
        return jdbc.update("DELETE FROM agent_event_audit WHERE received_at_ms < ?", receivedAtMs);
    }

    private static void appendCondition(StringBuilder where, String condition) {
        where.append(where.isEmpty() ? " WHERE " : " AND ").append(condition);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= 512) {
            return text;
        }
        return text.substring(0, 512);
    }

    /** 分页查询结果。 */
    public record AuditPage(List<EventAuditRecord> content, long total, int page, int size) {
    }
}
