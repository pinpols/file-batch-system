package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.SystemParameterEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleSystemParameterRepository extends Repository<SystemParameterEntity, Long> {

    @Query("""
            SELECT id, tenant_id, param_key, param_value, description, created_by, updated_by, created_at, updated_at
              FROM batch.system_parameter
             WHERE tenant_id = :tenantId
             ORDER BY param_key
            """)
    List<SystemParameterEntity> findAllByTenant(@Param("tenantId") String tenantId);

    @Query("""
            SELECT id, tenant_id, param_key, param_value, description, created_by, updated_by, created_at, updated_at
              FROM batch.system_parameter
             WHERE tenant_id = :tenantId AND param_key = :paramKey
             LIMIT 1
            """)
    Optional<SystemParameterEntity> findByTenantAndKey(@Param("tenantId") String tenantId,
                                                       @Param("paramKey") String paramKey);

    @Modifying
    @Query("""
            INSERT INTO batch.system_parameter (tenant_id, param_key, param_value, description, created_by, updated_by)
            VALUES (:tenantId, :paramKey, :paramValue, :description, :operator, :operator)
            ON CONFLICT (tenant_id, param_key) DO UPDATE
               SET param_value = :paramValue,
                   description = :description,
                   updated_by  = :operator,
                   updated_at  = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("tenantId") String tenantId,
                @Param("paramKey") String paramKey,
                @Param("paramValue") String paramValue,
                @Param("description") String description,
                @Param("operator") String operator);

    @Modifying
    @Query("DELETE FROM batch.system_parameter WHERE tenant_id = :tenantId AND param_key = :paramKey")
    void deleteByTenantAndKey(@Param("tenantId") String tenantId, @Param("paramKey") String paramKey);
}
