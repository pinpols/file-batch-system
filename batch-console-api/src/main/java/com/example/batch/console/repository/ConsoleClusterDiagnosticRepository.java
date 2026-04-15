package com.example.batch.console.repository;

import com.example.batch.console.domain.ConsoleJdbcQueryAnchor;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleClusterDiagnosticRepository
    extends Repository<ConsoleJdbcQueryAnchor, Long> {

  @Query(
      """
      SELECT name,
             lock_until AS "lockUntil",
             locked_at  AS "lockedAt",
             locked_by  AS "lockedBy"
        FROM batch.shedlock
       ORDER BY name
      """)
  List<ShedLockView> shedlockAll();

  @Query(
      """
      SELECT delivery_status AS "deliveryStatus",
             count(*)        AS cnt
        FROM batch.event_delivery_log
       WHERE tenant_id = :tenantId
       GROUP BY delivery_status
      """)
  List<DeliveryStatusCountView> eventDeliveryStatusCounts(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT count(*) FROM batch.outbox_event
       WHERE tenant_id = :tenantId AND publish_status = 'NEW'
      """)
  Long countPendingOutboxEvents(@Param("tenantId") String tenantId);

  record ShedLockView(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {

    public String getName() {
      return name;
    }

    public Instant getLockUntil() {
      return lockUntil;
    }

    public Instant getLockedAt() {
      return lockedAt;
    }

    public String getLockedBy() {
      return lockedBy;
    }
  }

  record DeliveryStatusCountView(String deliveryStatus, Long cnt) {

    public String getDeliveryStatus() {
      return deliveryStatus;
    }

    public Long getCnt() {
      return cnt;
    }
  }
}
