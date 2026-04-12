package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface QuotaRuntimeStateRepository extends CrudRepository<QuotaRuntimeStateRecord, Long> {

  QuotaRuntimeStateRecord findFirstByTenantIdAndQuotaScopeAndOwnerCode(
      String tenantId, String quotaScope, String ownerCode);

  @Query(
      """
      select * from batch.quota_runtime_state
      where quota_reset_policy in ('CALENDAR_DAY', 'SLIDING_WINDOW')
        and window_expires_at is not null
        and window_expires_at <= :now
      """)
  List<QuotaRuntimeStateRecord> findExpired(@Param("now") Instant now);

  @Query(
      """
      select * from batch.quota_runtime_state
      where tenant_id = :tenantId
      """)
  List<QuotaRuntimeStateRecord> findByTenantId(@Param("tenantId") String tenantId);
}
