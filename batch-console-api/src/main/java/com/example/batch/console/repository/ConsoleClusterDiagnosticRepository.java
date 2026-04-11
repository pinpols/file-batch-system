package com.example.batch.console.repository;

import com.example.batch.console.domain.ConsoleJdbcQueryAnchor;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ConsoleClusterDiagnosticRepository
        extends Repository<ConsoleJdbcQueryAnchor, Long> {

    @Query("SELECT name, lock_until, locked_at, locked_by FROM batch.shedlock ORDER BY name")
    List<ShedLockView> shedlockAll();

    @Query(
            """
            SELECT delivery_status, count(*) AS cnt
              FROM batch.event_delivery_log
             WHERE tenant_id = :tenantId
             GROUP BY delivery_status
            """)
    List<DeliveryStatusCountView> eventDeliveryStatusCounts(@Param("tenantId") String tenantId);

    @Query(
            """
            SELECT count(*) FROM batch.outbox_event
             WHERE tenant_id = :tenantId AND status = 'PENDING'
            """)
    Long countPendingOutboxEvents(@Param("tenantId") String tenantId);

    interface ShedLockView {
        String getName();

        Instant getLockUntil();

        Instant getLockedAt();

        String getLockedBy();
    }

    interface DeliveryStatusCountView {
        String getDeliveryStatus();

        Long getCnt();
    }
}
