package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncExportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncPreviewRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncExportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncSummaryResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.console.infrastructure.config.ConfigSyncImportExecutor.ImportContext;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleMapSupport;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 跨环境/跨租户的配置同步入口（export → preview → import）。
 *
 * <p>用途：把源租户（通常是某环境）的一批配置（job / workflow / fileChannel / fileTemplate等）打包成 {@code
 * ConfigSyncBundlePayload}，在目标租户侧 upsert 进去；实际 upsert 复用单租户初始化执行器，
 * 并在同一目标事务保存结果收据，支持 {@code dryRun} 预检不写入业务配置。
 *
 * <p>3 阶段：
 *
 * <ul>
 *   <li>{@link #export} — 源租户导出完整 bundle + summary（每类 count），前端据此呈现"可同步清单"。
 *   <li>{@link #preview} — 只返回 summary，不真正同步（比 import 轻量，用于前端挑选目标环境时的预判）。
 *   <li>{@link #importBundle} — 真正 upsert；每次都创建一条 {@code config_sync_log} 审计记录， 成功时 {@link
 *       #updateLog} 按每个目标租户的配置项结果更新状态与计数（SUCCESS/PARTIAL_FAILED）。
 * </ul>
 *
 * <p>初始审计先独立提交，再执行各目标事务。最终统计也独立提交，不能把日志失败当作已提交目标的回滚。
 * 瞬态错误仅有界重试审计更新，不重做导入；结果未知时保持RUNNING及未确认计数，留待核对。
 * 非strict单项失败由初始化结果承接，最终日志按配置项记PARTIAL_FAILED。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultConsoleConfigSyncApplicationService
    implements ConsoleConfigSyncApplicationService {
  private static final String SYSTEM_ERROR = "error.common.system_error";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_SUMMARY = "summary";
  private static final String KEY_TENANT_ID = "tenantId";

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleTenantConfigCopyService tenantConfigCopyService;
  private final ConfigSyncImportExecutor importExecutor;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final ConfigSyncLogMapper configSyncLogMapper;
  private final PlatformTransactionManager transactionManager;

  @Override
  public ConfigSyncExportResponse export(ConfigSyncExportRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getSourceTenantId());
    ConfigSyncBundlePayload bundle =
        tenantConfigCopyService.buildBundle(tenantId, request.getConfigTypes());
    String sourceEnv = request.getSourceEnv();
    String targetEnv = request.getTargetEnv();
    ConfigSyncSummaryResponse summary = ConfigSyncSummaryResponse.from(bundle);
    return new ConfigSyncExportResponse(
        tenantId,
        sourceEnv,
        targetEnv,
        summary,
        bundle);
  }

  @Override
  public ConfigSyncPreviewResponse preview(ConfigSyncPreviewRequest request) {
    String sourceTenantId = tenantGuard.resolveTenant(request.getSourceTenantId());
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConfigSyncBundlePayload bundle =
        tenantConfigCopyService.buildBundle(sourceTenantId, request.getConfigTypes());
    String sourceEnv = request.getSourceEnv();
    String targetEnv = request.getTargetEnv();
    ConfigSyncSummaryResponse summary = ConfigSyncSummaryResponse.from(bundle);
    return new ConfigSyncPreviewResponse(
        tenantId,
        sourceTenantId,
        sourceEnv,
        targetEnv,
        summary);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ConfigSyncImportResponse importBundle(ConfigSyncImportRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    if (request.getBundle() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.bundle_required");
    }
    List<String> targetTenantIds = request.getTargetTenantIds();
    boolean targetTenantSelectionIsInvalid =
        EmptyChecks.isNull(targetTenantIds) || EmptyChecks.isEmpty(targetTenantIds)
        || new HashSet<>(targetTenantIds).size() != targetTenantIds.size();
    if (targetTenantSelectionIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    TenantConfigBatchInitRequest initRequest = toInitRequest(request);
    ConsoleRequestMetadata metadata = metadataResolver.current();
    String operator = metadata.operatorId();
    if (EmptyChecks.isBlank(operator)) {
      operator = "system";
    }
    UUID operationId = UUID.randomUUID();
    String batchOperationId = operationId.toString();
    String requestKeyHash = requestKeyHash(metadata.idempotencyKey());
    InitialAuditIdentity identity = new InitialAuditIdentity(
        tenantId, operator, batchOperationId, requestKeyHash);
    Long logId;
    try {
      logId = auditTransaction(
          tenantId, () -> createLog(identity, request));
    } catch (DuplicateKeyException duplicateRequest) {
      Long existingLogId = EmptyChecks.isNull(requestKeyHash) ? null : auditTransaction(
          tenantId, () -> configSyncLogMapper.selectIdByRequestKey(tenantId, requestKeyHash));
      if (EmptyChecks.isNull(existingLogId)) {
        throw BizException.of(ResultCode.SYSTEM_ERROR, SYSTEM_ERROR, duplicateRequest);
      }
      String conflict = "Config Sync request already exists; reconcile logId=" + existingLogId;
      throw BizException.of(ResultCode.CONFLICT, "error.common.conflict_detail", conflict);
    } catch (DataAccessException auditFailure) {
      log.error(
          "config sync initial audit failed tenant={} operation={}",
          LogSanitizer.value(tenantId),
          batchOperationId,
          auditFailure);
      throw BizException.of(ResultCode.SYSTEM_ERROR, SYSTEM_ERROR, auditFailure);
    }
    TenantConfigBatchInitResponse response;
    try {
      ImportContext context = new ImportContext(tenantId, logId, operator, batchOperationId);
      response = importExecutor.execute(context, initRequest);
    } catch (RuntimeException ex) {
      try {
        auditTransaction(tenantId, () -> markLogUnresolved(logId, tenantId));
      } catch (RuntimeException auditFailure) {
        ex.addSuppressed(auditFailure);
        log.error(
            "config sync unresolved audit update failed tenant={} logId={}",
            LogSanitizer.value(tenantId),
            logId,
            auditFailure);
      }
      log.error(
          "config sync apply outcome unknown tenant={} logId={}",
          LogSanitizer.value(tenantId),
          logId,
          ex);
      throw BizException.of(ResultCode.SYSTEM_ERROR, SYSTEM_ERROR, ex);
    }
    // 已得到各目标的提交结果；此后只能重试审计，不能重做业务或伪造全部失败。
    finishAudit(logId, tenantId, request, response);
    ConfigSyncBundlePayload bundle = request.getBundle();
    ConfigSyncSummaryResponse summary = ConfigSyncSummaryResponse.from(bundle);
    return new ConfigSyncImportResponse(logId, summary, response);
  }

  @Override
  public PageResponse<ConfigSyncLogResponse> logs(String tenantId, int pageNo, int pageSize) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    PageRequest page = new PageRequest(pageNo, pageSize);
    long total = configSyncLogMapper.countByTenant(resolvedTenant);
    List<ConfigSyncLogResponse> items = configSyncLogMapper
        .selectByTenant(
            resolvedTenant, (long) (page.pageNo() - 1) * page.pageSize(), page.pageSize())
        .stream()
        .map(ConfigSyncLogResponse::from)
        .toList();
    return new PageResponse<>(total, page.pageNo(), page.pageSize(), items);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ConfigSyncLogResponse reconcile(String tenantId, Long logId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    String operator = metadataResolver.current().operatorId();
    try {
      return auditTransaction(resolvedTenant, () -> reconcileLocked(resolvedTenant, logId, operator));
    } catch (DataAccessException auditFailure) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, SYSTEM_ERROR, auditFailure);
    }
  }

  private ConfigSyncLogResponse reconcileLocked(String tenantId, Long logId, String operator) {
    Map<String, Object> row = configSyncLogMapper.selectByIdForUpdate(tenantId, logId);
    if (EmptyChecks.isNull(row)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found");
    }
    ConfigSyncLogResponse current = ConfigSyncLogResponse.from(row);
    if (!"RUNNING".equals(current.syncStatus())) {
      return current;
    }
    Object storedDetail = current.detailJson();
    if (EmptyChecks.isNull(storedDetail)) {
      return current;
    }
    AuditPlan plan = JsonUtils.fromJson(storedDetail.toString(), AuditPlan.class);
    List<String> targets = plan.targetTenantIds();
    boolean syncPlanTargetsAreInvalid =
        EmptyChecks.isNull(targets) || EmptyChecks.isEmpty(targets) || EmptyChecks.isNull(plan.summary())
        || targets.size() != new HashSet<>(targets).size();
    if (syncPlanTargetsAreInvalid) {
      return current;
    }
    List<TenantConfigBatchInitResponse.TenantInitResult> receipts =
        importExecutor.readReceipts(tenantId, logId, targets);
    if (receipts.size() != targets.size()) {
      // 无收据既可能未开始，也可能仍在事务中。不能据此伪造未执行或终态。
      return current;
    }
    List<String> receiptTenants = receipts.stream()
        .map(TenantConfigBatchInitResponse.TenantInitResult::tenantId)
        .toList();
    if (!new HashSet<>(targets).equals(new HashSet<>(receiptTenants))) {
      throw new IllegalStateException("Config sync receipts do not match original targets");
    }
    TenantConfigBatchInitResponse response = ConfigSyncImportExecutor.aggregate(
        plan.batchOperationId(), plan.dryRun(), receipts);
    AuditResult auditResult = new AuditResult(
        logId, tenantId, targets.size(), plan.summary().total(), operator);
    updateLog(auditResult, response);
    Map<String, Object> updated = configSyncLogMapper.selectByIdForUpdate(tenantId, logId);
    return ConfigSyncLogResponse.from(updated);
  }

  private record AuditPlan(
      String batchOperationId,
      ConfigSyncSummaryResponse summary,
      boolean dryRun,
      List<String> targetTenantIds) {}

  private record AuditResult(
      Long logId, String tenantId, int expectedTenantCount, int itemsPerTenant, String reconciledBy) {}

  private static String requestKeyHash(String requestKey) {
    if (EmptyChecks.isBlank(requestKey)) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = requestKey.trim().getBytes(StandardCharsets.UTF_8);
      byte[] hash = digest.digest(bytes);
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required for config sync request identity", failure);
    }
  }

  private int markLogUnresolved(Long logId, String tenantId) {
    Map<String, Object> unresolvedDetail =
        ConsoleMapSupport.mapOf("error", SYSTEM_ERROR, "outcomeKnown", false);
    String detailJson = JsonUtils.toJson(unresolvedDetail);
    Map<String, Object> unresolvedResult = ConsoleMapSupport.mapOf(
        KEY_TENANT_ID,
        tenantId,
        "id",
        logId,
        "syncStatus",
        "RUNNING",
        "successItems",
        0,
        "failedItems",
        0,
        "skippedItems",
        0,
        "mergeDetail",
        true,
        "detailJson",
        detailJson);
    int rows = configSyncLogMapper.updateResult(unresolvedResult);
    requireAuditRow(rows);
    return rows;
  }

  private void finishAudit(
      Long logId,
      String tenantId,
      ConfigSyncImportRequest request,
      TenantConfigBatchInitResponse response) {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        auditTransaction(tenantId, () -> {
          int targetTenantCount = request.getTargetTenantIds().size();
          ConfigSyncSummaryResponse summary = ConfigSyncSummaryResponse.from(request.getBundle());
          int totalConfigCount = summary.total();
          AuditResult auditResult = new AuditResult(
              logId, tenantId, targetTenantCount, totalConfigCount, null);
          updateLog(auditResult, response);
          return null;
        });
        return;
      } catch (TransientDataAccessException transientFailure) {
        if (attempt == 1) {
          log.error(
              "config sync final audit retry exhausted tenant={} logId={}",
              LogSanitizer.value(tenantId),
              logId,
              transientFailure);
          throw BizException.of(
              ResultCode.SYSTEM_ERROR, SYSTEM_ERROR, transientFailure);
        }
        log.warn(
            "config sync retrying final audit only tenant={} logId={}",
            LogSanitizer.value(tenantId),
            logId,
            transientFailure);
      } catch (DataAccessException auditFailure) {
        log.error(
            "config sync final audit failed after target completion tenant={} logId={}",
            LogSanitizer.value(tenantId),
            logId,
            auditFailure);
        throw BizException.of(ResultCode.SYSTEM_ERROR, SYSTEM_ERROR, auditFailure);
      }
    }
  }

  private <T> T auditTransaction(String tenantId, Supplier<T> operation) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> transaction.execute(status -> {
          configSyncLogMapper.setTenantContext(tenantId);
          return operation.get();
        }));
  }

  private static void requireAuditRow(int rows) {
    if (rows != 1) {
      throw new IllegalStateException("Config sync audit write must affect exactly one row");
    }
  }

  private record InitialAuditIdentity(
      String tenantId, String operator, String batchOperationId, String requestKeyHash) {}

  private Long createLog(InitialAuditIdentity identity, ConfigSyncImportRequest request) {
    ConfigSyncSummaryResponse summary = ConfigSyncSummaryResponse.from(request.getBundle());
    int totalItems = summary.total() * request.getTargetTenantIds().size();
    Map<String, Object> summaryDetail = new HashMap<>();
    summaryDetail.put(KEY_SUMMARY, summary);
    summaryDetail.put("dryRun", request.isDryRun());
    summaryDetail.put("batchOperationId", identity.batchOperationId());
    summaryDetail.put("targetTenantIds", request.getTargetTenantIds());
    if (EmptyChecks.isNotNull(identity.requestKeyHash())) {
      summaryDetail.put("requestKeyHash", identity.requestKeyHash());
    }
    String detailJson = JsonUtils.toJson(summaryDetail);
    Map<String, Object> params = ConsoleMapSupport.mapOf(
        KEY_TENANT_ID,
        identity.tenantId(),
        "syncDirection",
        "IMPORT",
        "sourceEnv",
        request.getSourceEnv(),
        "targetEnv",
        request.getTargetEnv(),
        "configTypes",
        String.join(",", configTypes()),
        "totalItems",
        totalItems,
        "successItems",
        0,
        "failedItems",
        0,
        "skippedItems",
        0,
        "syncStatus",
        "RUNNING",
        "detailJson",
        detailJson,
        "operatorId",
        identity.operator());
    int inserted = configSyncLogMapper.insert(params);
    requireAuditRow(inserted);
    Long logId = longValue(params.get("id"));
    if (EmptyChecks.isNull(logId)) {
      throw new IllegalStateException("Config sync audit insert did not return an id");
    }
    return logId;
  }

  private void updateLog(AuditResult audit, TenantConfigBatchInitResponse response) {
    int expectedTenantCount = audit.expectedTenantCount();
    int itemsPerTenant = audit.itemsPerTenant();
    int success = 0;
    int failed = 0;
    int skipped = 0;
    List<TenantConfigBatchInitResponse.TenantInitResult> results =
        EmptyChecks.isNull(response.results()) ? List.of() : response.results();
    for (TenantConfigBatchInitResponse.TenantInitResult result : results) {
      if (!result.success()) {
        failed += itemsPerTenant;
        continue;
      }
      for (TenantConfigBatchInitResponse.ItemStats stats : itemStats(result)) {
        if (EmptyChecks.isNull(stats)) {
          continue;
        }
        success += stats.created() + stats.updated() + stats.migrated();
        failed += stats.failed();
        skipped += stats.skipped();
      }
    }
    int missingTenantCount = Math.max(expectedTenantCount - results.size(), 0);
    failed += missingTenantCount * itemsPerTenant;
    boolean hasTenantFailures = results.stream().anyMatch(result -> !result.success());
    String syncStatus =
        failed > 0 || hasTenantFailures || missingTenantCount > 0 ? "PARTIAL_FAILED" : "SUCCESS";
    Map<String, Object> detail = JsonUtils.toMap(response);
    detail.put("outcomeKnown", true);
    detail.put("error", null);
    if (EmptyChecks.isNotNull(audit.reconciledBy())) {
      detail.put("reconciledBy", audit.reconciledBy());
    }
    String detailJson = JsonUtils.toJson(detail);
    Map<String, Object> updateFields = ConsoleMapSupport.mapOf(
        KEY_TENANT_ID,
        audit.tenantId(),
        "id",
        audit.logId(),
        "syncStatus",
        syncStatus,
        "successItems",
        success,
        "failedItems",
        failed,
        "skippedItems",
        skipped,
        "mergeDetail",
        true,
        "detailJson",
        detailJson);
    int rows = configSyncLogMapper.updateResult(updateFields);
    requireAuditRow(rows);
  }

  private static List<TenantConfigBatchInitResponse.ItemStats> itemStats(
      TenantConfigBatchInitResponse.TenantInitResult result) {
    return List.of(
        result.jobDefinitions(),
        result.workflowDefinitions(),
        result.fileChannels(),
        result.fileTemplates(),
        result.resourceQueues(),
        result.batchWindows(),
        result.businessCalendars(),
        result.quotaPolicies(),
        result.alertRoutings());
  }

  private TenantConfigBatchInitRequest toInitRequest(ConfigSyncImportRequest request) {
    TenantConfigBatchInitRequest initRequest = new TenantConfigBatchInitRequest();
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    ConfigSyncBundlePayload bundle = request.getBundle();
    initRequest.setJobDefinitions(bundle.getJobDefinitions());
    initRequest.setWorkflowDefinitions(bundle.getWorkflowDefinitions());
    initRequest.setFileChannels(bundle.getFileChannels());
    initRequest.setFileTemplates(bundle.getFileTemplates());
    initRequest.setResourceQueues(bundle.getResourceQueues());
    initRequest.setBatchWindows(bundle.getBatchWindows());
    initRequest.setBusinessCalendars(bundle.getBusinessCalendars());
    initRequest.setQuotaPolicies(bundle.getQuotaPolicies());
    initRequest.setAlertRoutings(bundle.getAlertRoutings());
    return initRequest;
  }

  private static List<String> configTypes() {
    return List.of(
        "jobDefinitions",
        "workflowDefinitions",
        "fileChannels",
        "fileTemplates",
        "resourceQueues",
        "batchWindows",
        "businessCalendars",
        "quotaPolicies",
        "alertRoutings");
  }

  private Long longValue(Object value) {
    return ConsoleMapSupport.longValue(value);
  }
}
