package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.ApiKeyEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleApiKeyRepository extends Repository<ApiKeyEntity, Long> {

    @Query("""
            SELECT id, tenant_id, key_name, key_prefix, key_hash, scopes, enabled,
                   expires_at, last_used_at, created_by, revoked_by, revoked_at, created_at
              FROM batch.api_key
             WHERE tenant_id = :tenantId
             ORDER BY created_at DESC
            """)
    List<ApiKeyEntity> findAllByTenant(@Param("tenantId") String tenantId);

    @Query("""
            SELECT id, tenant_id, key_name, key_prefix, key_hash, scopes, enabled,
                   expires_at, last_used_at, created_by, revoked_by, revoked_at, created_at
              FROM batch.api_key
             WHERE tenant_id = :tenantId AND id = :id
             LIMIT 1
            """)
    Optional<ApiKeyEntity> findByTenantAndId(@Param("tenantId") String tenantId,
                                              @Param("id") Long id);

    @Query("""
            SELECT id, tenant_id, key_name, key_prefix, key_hash, scopes, enabled,
                   expires_at, last_used_at, created_by, revoked_by, revoked_at, created_at
              FROM batch.api_key
             WHERE tenant_id = :tenantId AND key_name = :keyName
             LIMIT 1
            """)
    Optional<ApiKeyEntity> findByTenantAndName(@Param("tenantId") String tenantId,
                                                @Param("keyName") String keyName);

    @Modifying
    @Query("""
            INSERT INTO batch.api_key (tenant_id, key_name, key_prefix, key_hash, scopes, enabled, expires_at, created_by)
            VALUES (:tenantId, :keyName, :keyPrefix, :keyHash, :scopes, true, :expiresAt, :operator)
            """)
    void insert(@Param("tenantId") String tenantId,
                @Param("keyName") String keyName,
                @Param("keyPrefix") String keyPrefix,
                @Param("keyHash") String keyHash,
                @Param("scopes") String scopes,
                @Param("expiresAt") Instant expiresAt,
                @Param("operator") String operator);

    @Modifying
    @Query("""
            UPDATE batch.api_key
               SET enabled = false,
                   revoked_by = :operator,
                   revoked_at = CURRENT_TIMESTAMP
             WHERE tenant_id = :tenantId AND id = :id AND enabled = true
            """)
    void revoke(@Param("tenantId") String tenantId,
                @Param("id") Long id,
                @Param("operator") String operator);
}
