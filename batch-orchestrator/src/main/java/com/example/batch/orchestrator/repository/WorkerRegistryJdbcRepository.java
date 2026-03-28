package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.value.JsonbString;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkerRegistryJdbcRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public int touchHeartbeat(String tenantId,
                              String workerCode,
                              String nextStatus,
                              Instant heartbeatAt,
                              Integer currentLoad,
                              JsonbString capabilityTags) {
        return jdbcTemplate.update(
                """
                        update batch.worker_registry
                           set status = case
                                            when status in ('DECOMMISSIONED', 'DRAINING') then status
                                            else :nextStatus
                                        end,
                               heartbeat_at = :heartbeatAt,
                               current_load = :currentLoad,
                               capability_tags = coalesce(cast(:capabilityTags as jsonb), capability_tags),
                               updated_at = current_timestamp
                         where tenant_id = :tenantId
                           and worker_code = :workerCode
                        """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workerCode", workerCode)
                        .addValue("nextStatus", nextStatus)
                        .addValue("heartbeatAt", toTimestamp(heartbeatAt))
                        .addValue("currentLoad", currentLoad)
                        .addValue("capabilityTags", capabilityTags == null ? null : capabilityTags.getValue()));
    }

    public int markDecommissioned(String tenantId, String workerCode, Instant heartbeatAt) {
        return jdbcTemplate.update(
                """
                        update batch.worker_registry
                           set status = 'DECOMMISSIONED',
                               heartbeat_at = :heartbeatAt,
                               drain_started_at = null,
                               drain_deadline_at = null,
                               updated_at = current_timestamp
                         where tenant_id = :tenantId
                           and worker_code = :workerCode
                        """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workerCode", workerCode)
                        .addValue("heartbeatAt", toTimestamp(heartbeatAt)));
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
