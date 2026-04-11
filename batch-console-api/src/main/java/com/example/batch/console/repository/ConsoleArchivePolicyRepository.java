package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.ArchivePolicyEntity;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConsoleArchivePolicyRepository extends Repository<ArchivePolicyEntity, Long> {

    @Query(
            """
            SELECT * FROM batch.archive_policy
             WHERE tenant_id = :tenantId
             ORDER BY target_table
            """)
    List<ArchivePolicyEntity> findAllByTenant(@Param("tenantId") String tenantId);

    @Query(
            """
            SELECT * FROM batch.archive_policy
             WHERE tenant_id = :tenantId AND target_table = :targetTable
             LIMIT 1
            """)
    Optional<ArchivePolicyEntity> findByTenantAndTable(
            @Param("tenantId") String tenantId, @Param("targetTable") String targetTable);

    @Modifying
    @Query(
            """
            INSERT INTO batch.archive_policy (tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by)
            VALUES (:tenantId, :targetTable, :retentionDays, :archiveEnabled, :cleanupEnabled, :batchSize, :description, :operator, :operator)
            ON CONFLICT (tenant_id, target_table) DO UPDATE
               SET retention_days  = :retentionDays,
                   archive_enabled = :archiveEnabled,
                   cleanup_enabled = :cleanupEnabled,
                   batch_size      = :batchSize,
                   description     = :description,
                   updated_by      = :operator,
                   updated_at      = CURRENT_TIMESTAMP
            """)
    void upsert(
            @Param("tenantId") String tenantId,
            @Param("targetTable") String targetTable,
            @Param("retentionDays") int retentionDays,
            @Param("archiveEnabled") boolean archiveEnabled,
            @Param("cleanupEnabled") boolean cleanupEnabled,
            @Param("batchSize") int batchSize,
            @Param("description") String description,
            @Param("operator") String operator);
}
