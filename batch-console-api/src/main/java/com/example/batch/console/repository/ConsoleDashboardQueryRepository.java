package com.example.batch.console.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleDashboardQueryRepository extends Repository<Object, Long> {

    @Query("""
            SELECT instance_status AS status, count(1) AS count
              FROM batch.job_instance
             WHERE tenant_id = :tenantId
             GROUP BY instance_status
            """)
    List<StatusCountView> jobStatusCounts(@Param("tenantId") String tenantId);

    @Query("""
            SELECT cast(created_at as date) AS day, instance_status AS status, count(1) AS count
              FROM batch.job_instance
             WHERE tenant_id = :tenantId
               AND created_at >= current_date - :days
             GROUP BY day, instance_status
             ORDER BY day
            """)
    List<DayStatusCountView> jobDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

    @Query("""
            SELECT trigger_type AS type, count(1) AS count
              FROM batch.trigger_request
             WHERE tenant_id = :tenantId
             GROUP BY trigger_type
            """)
    List<TypeCountView> triggerTypeCounts(@Param("tenantId") String tenantId);

    @Query("""
            SELECT cast(created_at as date) AS day, count(1) AS count
              FROM batch.trigger_request
             WHERE tenant_id = :tenantId
               AND created_at >= current_date - :days
             GROUP BY day
             ORDER BY day
            """)
    List<DayCountView> triggerDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

    @Query("""
            SELECT status, count(1) AS count
              FROM batch.worker_registry
             WHERE tenant_id = :tenantId
             GROUP BY status
            """)
    List<StatusCountView> workerStatusCounts(@Param("tenantId") String tenantId);

    @Query("""
            SELECT worker_group AS workerGroup, status, count(1) AS count
              FROM batch.worker_registry
             WHERE tenant_id = :tenantId
             GROUP BY worker_group, status
             ORDER BY worker_group
            """)
    List<WorkerGroupStatusCountView> workerGroupStatusCounts(@Param("tenantId") String tenantId);

    @Query("""
            SELECT p.worker_code AS workerCode, count(1) AS activePartitions
              FROM batch.job_partition p
             WHERE p.tenant_id = :tenantId
               AND p.partition_status IN ('READY', 'RUNNING')
               AND p.worker_code IS NOT NULL
             GROUP BY p.worker_code
             ORDER BY activePartitions DESC
            """)
    List<ActivePartitionView> activePartitionsByWorker(@Param("tenantId") String tenantId);

    @Query("""
            SELECT severity, count(1) AS count
              FROM batch.alert_event
             WHERE tenant_id = :tenantId
             GROUP BY severity
            """)
    List<SeverityCountView> alertSeverityCounts(@Param("tenantId") String tenantId);

    @Query("""
            SELECT cast(created_at as date) AS day, severity, count(1) AS count
              FROM batch.alert_event
             WHERE tenant_id = :tenantId
               AND created_at >= current_date - :days
             GROUP BY day, severity
             ORDER BY day
            """)
    List<DaySeverityCountView> alertDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

    @Query("""
            SELECT count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at > deadline_at) AS breached,
                   count(1) FILTER (WHERE deadline_at IS NOT NULL AND (finished_at IS NULL OR finished_at <= deadline_at)) AS onTime,
                   count(1) FILTER (WHERE deadline_at IS NOT NULL) AS totalWithSla,
                   round(avg(EXTRACT(EPOCH FROM (finished_at - started_at)))::numeric, 2) AS avgDurationSeconds
              FROM batch.job_instance
             WHERE tenant_id = :tenantId
               AND created_at >= current_date - :days
               AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED')
            """)
    SlaStatsView slaStats(@Param("tenantId") String tenantId, @Param("days") int days);

    @Query("""
            SELECT cast(created_at as date) AS day,
                   count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at > deadline_at) AS breached,
                   count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at <= deadline_at) AS onTime
              FROM batch.job_instance
             WHERE tenant_id = :tenantId
               AND created_at >= current_date - :days
               AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED')
             GROUP BY day
             ORDER BY day
            """)
    List<SlaDayView> slaDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

    interface StatusCountView {
        String getStatus();
        Long getCount();
    }

    interface DayStatusCountView {
        LocalDate getDay();
        String getStatus();
        Long getCount();
    }

    interface TypeCountView {
        String getType();
        Long getCount();
    }

    interface DayCountView {
        LocalDate getDay();
        Long getCount();
    }

    interface WorkerGroupStatusCountView {
        String getWorkerGroup();
        String getStatus();
        Long getCount();
    }

    interface ActivePartitionView {
        String getWorkerCode();
        Long getActivePartitions();
    }

    interface SeverityCountView {
        String getSeverity();
        Long getCount();
    }

    interface DaySeverityCountView {
        LocalDate getDay();
        String getSeverity();
        Long getCount();
    }

    interface SlaStatsView {
        Long getBreached();
        Long getOnTime();
        Long getTotalWithSla();
        BigDecimal getAvgDurationSeconds();
    }

    interface SlaDayView {
        LocalDate getDay();
        Long getBreached();
        Long getOnTime();
    }
}
