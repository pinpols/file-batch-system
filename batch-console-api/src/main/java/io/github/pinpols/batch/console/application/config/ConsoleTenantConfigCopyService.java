package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPreviewRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigMatrixResponse;
import java.util.Set;

/**
 * 跨租户配置复制应用服务。
 *
 * <p>一次导出、复制或预览的源配置查询使用独立只读快照；目标写入按租户各自提交。
 *
 * <p>P1(2026-05-23 audit):原实现 {@code
 * io.github.pinpols.batch.console.web.ConsoleTenantConfigCopyService} 标 {@code @Service} 却放在 {@code
 * web} 包,违反 application / infrastructure 分层。本接口位于 {@code application/config},默认实现见 {@code
 * infrastructure/config/DefaultConsoleTenantConfigCopyService}。
 */
public interface ConsoleTenantConfigCopyService {

  /**
   * 将 source 租户的指定配置类型复制到 target 租户集合。
   *
   * @param request 复制请求(源 / 目标 / 配置类型 / 模式 / dryRun)
   * @param operator 操作人标识
   * @param batchOperationId 批次操作 ID,用于审计关联
   * @return 每个目标租户的复制结果汇总
   */
  TenantConfigBatchInitResponse copy(
      TenantConfigCopyRequest request, String operator, String batchOperationId);

  /** 使用原请求身份登记逐目标回执；空身份沿用原有入口。 */
  TenantConfigBatchInitResponse copy(
      TenantConfigCopyRequest request,
      String operator,
      String batchOperationId,
      String requestHash);

  /** 将源租户配置包复制到目标租户前，提供只读差异预览。 */
  TenantConfigDiffPreviewResponse preview(TenantConfigPreviewRequest request);

  /** 基于租户配置包预览目标租户覆盖项，只读不落库。 */
  TenantConfigDiffPreviewResponse previewOverlay(TenantConfigPreviewRequest request);

  /** 比较多个租户的同一作业配置。 */
  TenantConfigMatrixResponse matrix(TenantConfigMatrixRequest request);

  /**
   * 从源租户读取指定配置类型构建 bundle 载荷,不下推。
   *
   * @param sourceTenantId 源租户 ID
   * @param configTypes 要包含的配置类型;null / 空集合表示全部 9 种
   */
  ConfigSyncBundlePayload buildBundle(String sourceTenantId, Set<ConfigType> configTypes);

  /**
   * 针对单个 jobCode 构建最小相关配置 bundle(job + 关联 workflow / queue / window / calendar /
   * fileTemplate / fileChannel + 全部 quota / alert),用于 job 级跨租户复制。
   */
  ConfigSyncBundlePayload buildJobBundle(String sourceTenantId, String jobCode);
}
