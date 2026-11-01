package io.github.pinpols.batch.console.domain.observability.application;

import io.github.pinpols.batch.console.application.contract.response.ExcelDownload;
import io.github.pinpols.batch.console.application.contract.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.ConfigChangeLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.ConfigReleaseQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.SecretVersionQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.WorkerRegistryQueryRequest;

/** 控制台报表 Excel 导出应用服务：将各类查询结果汇总为可下载的 Excel 流。 */
public interface ConsoleReportExcelApplicationService {

  /** 导出配置发布单列表。 */
  ExcelDownload exportConfigReleases(ConfigReleaseQueryRequest request);

  /** 导出密钥版本列表。 */
  ExcelDownload exportSecretVersions(SecretVersionQueryRequest request);

  /** 导出配置变更日志。 */
  ExcelDownload exportConfigChangeLogs(ConfigChangeLogQueryRequest request);

  /** 导出审计日志。 */
  ExcelDownload exportAuditLogs(AuditLogQueryRequest request);

  /** 导出当前调度快照。 */
  ExcelDownload exportSchedulerSnapshot(String tenantId);

  /** 导出调度快照历史。 */
  ExcelDownload exportSchedulerSnapshotHistory(String tenantId, int limit);

  /** 导出 Worker 注册信息。 */
  ExcelDownload exportWorkers(WorkerRegistryQueryRequest request);

  /** 导出 Outbox 重试日志。 */
  ExcelDownload exportOutboxRetries(OutboxRetryLogQueryRequest request);

  /** 导出 Outbox 投递日志。 */
  ExcelDownload exportOutboxDeliveries(
      OutboxDeliveryLogQueryRequest request);
}
