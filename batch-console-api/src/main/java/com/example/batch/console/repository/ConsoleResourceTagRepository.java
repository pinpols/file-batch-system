package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.ResourceTagEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleResourceTagRepository extends Repository<ResourceTagEntity, Long> {

    @Query("""
            SELECT id, tenant_id, resource_type, resource_code, tag_key, tag_value, created_by, created_at
              FROM batch.resource_tag
             WHERE tenant_id = :tenantId
               AND resource_type = :resourceType
               AND resource_code = :resourceCode
             ORDER BY tag_key
            """)
    List<ResourceTagEntity> findByResource(@Param("tenantId") String tenantId,
                                           @Param("resourceType") String resourceType,
                                           @Param("resourceCode") String resourceCode);

    @Query("""
            SELECT id, tenant_id, resource_type, resource_code, tag_key, tag_value, created_by, created_at
              FROM batch.resource_tag
             WHERE tenant_id = :tenantId
               AND tag_key = :tagKey
             ORDER BY resource_type, resource_code
            """)
    List<ResourceTagEntity> findByTagKey(@Param("tenantId") String tenantId,
                                         @Param("tagKey") String tagKey);

    @Query("""
            SELECT id, tenant_id, resource_type, resource_code, tag_key, tag_value, created_by, created_at
              FROM batch.resource_tag
             WHERE tenant_id = :tenantId
               AND tag_key = :tagKey
               AND tag_value = :tagValue
             ORDER BY resource_type, resource_code
            """)
    List<ResourceTagEntity> findByTagKeyAndValue(@Param("tenantId") String tenantId,
                                                  @Param("tagKey") String tagKey,
                                                  @Param("tagValue") String tagValue);

    @Query("""
            SELECT DISTINCT tag_key
              FROM batch.resource_tag
             WHERE tenant_id = :tenantId
             ORDER BY tag_key
            """)
    List<String> findDistinctTagKeys(@Param("tenantId") String tenantId);

    @Query("""
            SELECT id, tenant_id, resource_type, resource_code, tag_key, tag_value, created_by, created_at
              FROM batch.resource_tag
             WHERE tenant_id = :tenantId
               AND resource_type = :resourceType
               AND resource_code = :resourceCode
               AND tag_key = :tagKey
             LIMIT 1
            """)
    Optional<ResourceTagEntity> findByResourceAndKey(@Param("tenantId") String tenantId,
                                                      @Param("resourceType") String resourceType,
                                                      @Param("resourceCode") String resourceCode,
                                                      @Param("tagKey") String tagKey);

    @Modifying
    @Query("""
            INSERT INTO batch.resource_tag (tenant_id, resource_type, resource_code, tag_key, tag_value, created_by)
            VALUES (:tenantId, :resourceType, :resourceCode, :tagKey, :tagValue, :operator)
            ON CONFLICT (tenant_id, resource_type, resource_code, tag_key) DO UPDATE
               SET tag_value  = :tagValue,
                   created_by = :operator,
                   created_at = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("tenantId") String tenantId,
                @Param("resourceType") String resourceType,
                @Param("resourceCode") String resourceCode,
                @Param("tagKey") String tagKey,
                @Param("tagValue") String tagValue,
                @Param("operator") String operator);

    @Modifying
    @Query("""
            DELETE FROM batch.resource_tag
             WHERE tenant_id = :tenantId
               AND resource_type = :resourceType
               AND resource_code = :resourceCode
               AND tag_key = :tagKey
            """)
    void deleteByResourceAndKey(@Param("tenantId") String tenantId,
                                @Param("resourceType") String resourceType,
                                @Param("resourceCode") String resourceCode,
                                @Param("tagKey") String tagKey);

    @Modifying
    @Query("""
            DELETE FROM batch.resource_tag
             WHERE tenant_id = :tenantId
               AND resource_type = :resourceType
               AND resource_code = :resourceCode
            """)
    void deleteAllByResource(@Param("tenantId") String tenantId,
                             @Param("resourceType") String resourceType,
                             @Param("resourceCode") String resourceCode);
}
