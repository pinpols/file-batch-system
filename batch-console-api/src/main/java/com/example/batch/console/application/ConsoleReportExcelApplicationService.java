package com.example.batch.console.application;

import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/**
 * 控制台报表 Excel 导出应用服务：将各类查询结果汇总为可下载的 Excel 流。
 */
public interface ConsoleReportExcelApplicationService {

    /** 导出配置发布单列表。 */
    ResponseEntity<InputStreamResource> exportConfigReleases(ConfigReleaseQueryRequest request);

    /** 导出密钥版本列表。 */
    ResponseEntity<InputStreamResource> exportSecretVersions(SecretVersionQueryRequest request);

    /** 导出配置变更日志。 */
    ResponseEntity<InputStreamResource> exportConfigChangeLogs(ConfigChangeLogQueryRequest request);

    /** 导出审计日志。 */
    ResponseEntity<InputStreamResource> exportAuditLogs(AuditLogQueryRequest request);

    /** 导出当前调度快照。 */
    ResponseEntity<InputStreamResource> exportSchedulerSnapshot(String tenantId);

    /** 导出调度快照历史。 */
    ResponseEntity<InputStreamResource> exportSchedulerSnapshotHistory(String tenantId, int limit);

    /** 导出 Worker 注册信息。 */
    ResponseEntity<InputStreamResource> exportWorkers(WorkerRegistryQueryRequest request);

    /** 导出 Outbox 重试日志。 */
    ResponseEntity<InputStreamResource> exportOutboxRetries(OutboxRetryLogQueryRequest request);

    /** 导出 Outbox 投递日志。 */
    ResponseEntity<InputStreamResource> exportOutboxDeliveries(OutboxDeliveryLogQueryRequest request);
}
