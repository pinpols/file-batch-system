package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotRecord;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TenantSchedulerSnapshotRepository extends CrudRepository<TenantSchedulerSnapshotRecord, Long> {

    @Query("""
            select * from batch.tenant_scheduler_snapshot
            where tenant_id = :tenantId
            order by snapshot_at desc
            limit :lim
            """)
    List<TenantSchedulerSnapshotRecord> listRecent(@Param("tenantId") String tenantId, @Param("lim") int limit);
}
