package com.example.batch.console.repository;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleMetaQueryRepository extends Repository<Object, Long> {

    @Query("""
            SELECT queue_code AS code, queue_code AS label
              FROM batch.resource_queue
             WHERE tenant_id = :tenantId
               AND enabled = TRUE
             ORDER BY queue_code
            """)
    List<SimpleOptionView> queueOptions(@Param("tenantId") String tenantId);

    @Query("""
            SELECT calendar_code AS code, calendar_code AS label
              FROM batch.business_calendar
             WHERE tenant_id = :tenantId
               AND enabled = TRUE
             ORDER BY calendar_code
            """)
    List<SimpleOptionView> calendarOptions(@Param("tenantId") String tenantId);

    @Query("""
            SELECT window_code AS code, window_code AS label
              FROM batch.batch_window
             WHERE tenant_id = :tenantId
               AND enabled = TRUE
             ORDER BY window_code
            """)
    List<SimpleOptionView> windowOptions(@Param("tenantId") String tenantId);

    @Query("""
            SELECT DISTINCT worker_group AS code, worker_group AS label
              FROM batch.worker_registry
             WHERE tenant_id = :tenantId
               AND status = 'ONLINE'
             ORDER BY worker_group
            """)
    List<SimpleOptionView> workerGroupOptions(@Param("tenantId") String tenantId);

    interface SimpleOptionView {
        String getCode();
        String getLabel();
    }
}
