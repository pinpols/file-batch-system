package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 分发渠道健康状态持久化仓库。
 */
@Repository
@RequiredArgsConstructor
public class DispatchChannelHealthRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> findEnabledProbeChannels(List<String> types, int limit) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(types);
        String inClause = String.join(",", types.stream().map(ignored -> "?").toList());
        String sql = """
                select *
                from batch.file_channel_config
                where enabled = true
                  and channel_type in (%s)
                order by tenant_id, channel_code
                limit ?
                """.formatted(inClause);
        args.add(limit);
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    public DispatchChannelHealthSnapshot findHealth(String tenantId, String channelCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                        select *
                        from batch.file_channel_health
                        where tenant_id = ? and channel_code = ?
                        """,
                tenantId, channelCode
        );
        if (rows.isEmpty()) {
            return null;
        }
        return toSnapshot(rows.get(0));
    }

    public void upsertHealth(DispatchChannelHealthSnapshot s) {
        jdbcTemplate.update("""
                        insert into batch.file_channel_health (
                            tenant_id, channel_code, channel_type, health_status, consecutive_failures,
                            last_probe_at, last_success_at, last_failure_at, next_probe_at,
                            probe_message, probe_evidence, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                        on conflict (tenant_id, channel_code) do update set
                            channel_type = excluded.channel_type,
                            health_status = excluded.health_status,
                            consecutive_failures = excluded.consecutive_failures,
                            last_probe_at = excluded.last_probe_at,
                            last_success_at = excluded.last_success_at,
                            last_failure_at = excluded.last_failure_at,
                            next_probe_at = excluded.next_probe_at,
                            probe_message = excluded.probe_message,
                            probe_evidence = excluded.probe_evidence,
                            updated_at = current_timestamp
                        """,
                s.tenantId(), s.channelCode(), s.channelType(), s.healthStatus(), s.consecutiveFailures(),
                toTimestamp(s.lastProbeAt()), toTimestamp(s.lastSuccessAt()), toTimestamp(s.lastFailureAt()),
                toTimestamp(s.nextProbeAt()), s.probeMessage(), s.probeEvidence()
        );
    }

    private DispatchChannelHealthSnapshot toSnapshot(Map<String, Object> row) {
        return new DispatchChannelHealthSnapshot(
                stringValue(row.get("tenant_id")),
                stringValue(row.get("channel_code")),
                stringValue(row.get("channel_type")),
                stringValue(row.get("health_status")),
                intValue(row.get("consecutive_failures")),
                instantValue(row.get("last_probe_at")),
                instantValue(row.get("last_success_at")),
                instantValue(row.get("last_failure_at")),
                instantValue(row.get("next_probe_at")),
                stringValue(row.get("probe_message")),
                stringValue(row.get("probe_evidence"))
        );
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(text);
    }

    private Instant instantValue(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Instant.parse(text);
    }
}
