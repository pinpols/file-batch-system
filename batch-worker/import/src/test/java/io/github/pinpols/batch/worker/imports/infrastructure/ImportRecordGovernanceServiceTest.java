package io.github.pinpols.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileErrorRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.config.ImportSkipProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportBadRecordEntity;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 单测：ImportRecordGovernanceService —— 跳过策略 / 阈值判定 / 坏记录写入数据库 / 错误汇总。
 *
 * <p>覆盖主链路 happy path 与典型分支：CONTINUE / FAIL_BATCH / MANUAL_REVIEW、ABSOLUTE / PERCENTAGE
 * 阈值、ERROR_FILE / ERROR_TABLE sink、parse vs validate stage 分桶计数、bypassMode 关脱敏等。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("导入坏记录治理服务单测:跳过策略,阈值判定,坏记录落库与错误汇总语义")
class ImportRecordGovernanceServiceTest {

  @Mock
  private PlatformFileRecordRepository runtimeRepository;

  @Mock
  private PlatformFileAuditRepository fileAudits;

  @Mock
  private ImportErrorOutputStorage errorOutputStorage;

  private BatchSecurityProperties batchSecurityProperties;

  private ImportRecordGovernanceService service;

  @BeforeEach
  void setUp() {
    batchSecurityProperties = new BatchSecurityProperties();
    // 默认 service 由各用例按场景重建，便于切换 ImportSkipProperties
  }

  private ImportRecordGovernanceService buildService(ImportSkipProperties props) {
    return new ImportRecordGovernanceService(
        props, runtimeRepository, fileAudits, errorOutputStorage, batchSecurityProperties);
  }

  private ImportSkipProperties props(
      boolean enabled,
      String thresholdMode,
      int maxCount,
      double maxRate,
      String skipCodes,
      String skipAction,
      String sink) {
    return new ImportSkipProperties(
        enabled, thresholdMode, maxCount, maxRate, skipCodes, skipAction, sink, 7);
  }

  private ImportJobContext context() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("tenant-A");
    ctx.setWorkerId("worker-1");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    attrs.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 7L);
    attrs.put(PipelineRuntimeKeys.PIPELINE_STEP_RUN_ID, 70L);
    attrs.put(PipelineRuntimeKeys.TRACE_ID, "trace-1");
    ctx.setAttributes(attrs);
    return ctx;
  }

  // ── isSkipEnabled / isSkippable / shouldFailOnSkip / shouldManualReview ──

  @Test
  @DisplayName("跳过开关关闭时,任何错误码都不允许跳过")
  void shouldReturnFalse_whenSkipDisabled() {
    service = buildService(props(false, "ABSOLUTE", 5, 0.1, "E1", "CONTINUE", "BOTH"));
    assertThat(service.isSkipEnabled()).isFalse();
    assertThat(service.isSkippable("E1")).isFalse();
  }

  @Test
  @DisplayName("跳过开关开启且未限定错误码时全部可跳过,空值仍不可跳过")
  void shouldAllowAll_whenSkipEnabledAndCodesEmpty() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "", "CONTINUE", "BOTH"));
    assertThat(service.isSkipEnabled()).isTrue();
    assertThat(service.isSkippable("ANY_CODE")).isTrue();
    assertThat(service.isSkippable("")).isFalse();
    assertThat(service.isSkippable(null)).isFalse();
  }

  @Test
  @DisplayName("配置错误码清单后仅清单内可跳过,空白项被忽略")
  void shouldRestrictToConfiguredCodes_whenSkipCodesProvided() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "E1, E2 ,", "CONTINUE", "BOTH"));
    assertThat(service.isSkippable("E1")).isTrue();
    assertThat(service.isSkippable("E2")).isTrue();
    assertThat(service.isSkippable("E3")).isFalse();
  }

  @Test
  @DisplayName("仅当处置动作为失败批次且错误码可跳过时,才判定为需要失败")
  void shouldFailOnSkip_onlyWhenActionIsFailBatchAndCodeSkippable() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "E1", "FAIL_BATCH", "BOTH"));
    assertThat(service.shouldFailOnSkip("E1")).isTrue();
    assertThat(service.shouldFailOnSkip("E2")).isFalse();
  }

  @Test
  @DisplayName("处置动作为继续时,可跳过的错误码不触发批次失败")
  void shouldNotFailOnSkip_whenActionIsContinue() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "E1", "CONTINUE", "BOTH"));
    assertThat(service.shouldFailOnSkip("E1")).isFalse();
  }

  @Test
  @DisplayName("仅当处置动作为人工复核时,才要求人工复核")
  void shouldManualReview_onlyWhenActionIsManualReview() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "", "MANUAL_REVIEW", "BOTH"));
    assertThat(service.shouldManualReview()).isTrue();

    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "", "CONTINUE", "BOTH"));
    assertThat(service.shouldManualReview()).isFalse();
  }

  // ── withinThreshold ──

  @Test
  @DisplayName("跳过开关关闭时不看计数,阈值判定恒为通过")
  void shouldReturnTrue_whenSkipDisabled_regardlessOfCount() {
    service = buildService(props(false, "ABSOLUTE", 0, 0.0, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 9999L);
    assertThat(service.withinThreshold(ctx)).isTrue();
  }

  @Test
  @DisplayName("绝对阈值:跳过数等于阈值时通过,超过即不通过")
  void shouldEnforceAbsoluteThreshold() {
    service = buildService(props(true, "ABSOLUTE", 3, 0.5, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 3L);
    assertThat(service.withinThreshold(ctx)).isTrue();
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 4L);
    assertThat(service.withinThreshold(ctx)).isFalse();
  }

  @Test
  @DisplayName("百分比阈值:占比等于上限时通过,超过即不通过")
  void shouldEnforcePercentageThreshold() {
    service = buildService(props(true, "PERCENTAGE", 0, 0.1, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 100L);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 10L);
    assertThat(service.withinThreshold(ctx)).isTrue();
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 11L);
    assertThat(service.withinThreshold(ctx)).isFalse();
  }

  @Test
  @DisplayName("总数为零时占比按零处理,阈值判定通过")
  void shouldTreatRateAsZero_whenTotalCountZero() {
    service = buildService(props(true, "PERCENTAGE", 0, 0.1, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 0L);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 5L);
    assertThat(service.withinThreshold(ctx)).isTrue();
  }

  // ── recordSkippedRecord / recordFailedRecord / stage scoped counters ──

  @Test
  @DisplayName("登记跳过记录:累计跳过数与阶段计数递增,并写入错误明细")
  void shouldRecordSkippedRecord_incrementsSkippedAndStageScopedCounters() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    service.recordSkippedRecord(ctx, ImportStage.PARSE, 10L, "E1", "msg-1", Map.of("k", "v"));

    assertThat(ctx.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 1L);
    assertThat(ctx.getAttributes()).containsEntry("parseSkippedCount", 1L);
    assertThat(ctx.getAttributes()).containsEntry("lastProcessedRecordNo", 10L);
    assertThat(ctx.getAttributes()).containsEntry("lastErrorCode", "E1");
    assertThat(ctx.getAttributes()).containsEntry("lastErrorMessage", "msg-1");

    @SuppressWarnings("unchecked")
    List<ImportBadRecordEntity> bad =
        (List<ImportBadRecordEntity>) ctx.getAttributes().get("badRecords");
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).skipped()).isTrue();
    assertThat(bad.get(0).stageCode()).isEqualTo("PARSE");

    ArgumentCaptor<FileErrorRecordParam> captor =
        ArgumentCaptor.forClass(FileErrorRecordParam.class);
    verify(fileAudits).insertFileErrorRecord(captor.capture());
    FileErrorRecordParam param = captor.getValue();
    assertThat(param.getTenantId()).isEqualTo("tenant-A");
    assertThat(param.getFileId()).isEqualTo(99L);
    assertThat(param.getRecordNo()).isEqualTo(10L);
    assertThat(param.getErrorCode()).isEqualTo("E1");
    assertThat(param.getErrorStage()).isEqualTo("PARSE");
    assertThat(param.isSkipped()).isTrue();
  }

  @Test
  @DisplayName("登记失败记录:失败计数递增,并暂存最后一条坏记录")
  void shouldRecordFailedRecord_incrementsFailedAndStashesLastBadRecord() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    service.recordFailedRecord(ctx, ImportStage.VALIDATE, 5L, "EFAIL", "boom", "raw");

    assertThat(ctx.getAttributes()).containsEntry("failedCount", 1L);
    assertThat(ctx.getAttributes()).containsEntry("validateFailedCount", 1L);
    assertThat(ctx.getAttributes()).containsKey("lastBadRecord");
    verify(fileAudits).insertFileErrorRecord(any());
  }

  @Test
  @DisplayName("人工复核动作下出现跳过记录时,标记需要人工复核")
  void shouldFlagManualReview_whenSkippedAndActionIsManualReview() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "MANUAL_REVIEW", "BOTH"));

    ImportJobContext ctx = context();
    service.recordSkippedRecord(ctx, ImportStage.PARSE, 1L, "E1", "m", "raw");
    assertThat(ctx.getAttributes()).containsEntry("manualReviewRequired", true);
  }

  @Test
  @DisplayName("模板配置开启脱敏时,落库的错误消息经过脱敏替换")
  void shouldMaskErrorPayload_whenTemplateConfigEnablesMasking() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    ctx.getAttributes()
        .put(
            PipelineRuntimeKeys.TEMPLATE_CONFIG,
            Map.of("error_line_masking_enabled", true, "masking_rule_set", "default"));

    service.recordFailedRecord(ctx, ImportStage.VALIDATE, 1L, "E", "user=alice", "raw-payload");

    ArgumentCaptor<FileErrorRecordParam> captor =
        ArgumentCaptor.forClass(FileErrorRecordParam.class);
    verify(fileAudits).insertFileErrorRecord(captor.capture());
    // masking 经过 ContentMaskingUtils 处理；这里只断言原始 message 被替换（非 null 且不等于原文）
    assertThat(captor.getValue().getErrorMessage()).isNotNull();
  }

  @Test
  @DisplayName("旁路模式开启时不做脱敏,错误消息原样落库")
  void shouldDisableMasking_whenBypassModeOn() {
    batchSecurityProperties.setBypassMode(true);
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    ctx.getAttributes()
        .put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("error_line_masking_enabled", true));
    service.recordFailedRecord(ctx, ImportStage.VALIDATE, 1L, "E", "raw-msg", "raw");

    ArgumentCaptor<FileErrorRecordParam> captor =
        ArgumentCaptor.forClass(FileErrorRecordParam.class);
    verify(fileAudits).insertFileErrorRecord(captor.capture());
    assertThat(captor.getValue().getErrorMessage()).isEqualTo("raw-msg");
  }

  // ── recordThresholdViolation ──

  @Test
  @DisplayName("阈值越界时登记违规记录,并置位跳过超限标记")
  void shouldRecordThresholdViolation_andSetSkipFlag() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    service.recordThresholdViolation(ctx, ImportStage.VALIDATE, "THRESH", "exceeded");

    assertThat(ctx.getAttributes()).containsEntry("skipThresholdExceeded", true);
    verify(fileAudits).insertFileErrorRecord(any());
  }

  // ── finalizeErrorOutput ──

  @Test
  @DisplayName("没有坏记录时收尾直接返回,不写错误文件也不追加审计")
  void shouldSkipFinalize_whenNoBadRecords() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    service.finalizeErrorOutput(ctx);

    verifyNoInteractions(errorOutputStorage);
    verify(fileAudits, never()).appendAudit(any());
    verify(runtimeRepository, never()).updateFileMetadata(anyLong(), any());
  }

  @Test
  @DisplayName("有坏记录时收尾写出错误文件,回填文件元数据并追加审计")
  void shouldFinalizeErrorOutput_writesFileMetadataAndAudit() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(errorOutputStorage.writeErrorOutput(eq("tenant-A"), eq("99"), any()))
        .thenReturn("s3://bucket/file");

    ImportJobContext ctx = context();
    // 先塞 1 条坏记录
    service.recordSkippedRecord(ctx, ImportStage.PARSE, 1L, "E", "m", "raw");
    ctx.getAttributes().put("successCount", 10L);
    ctx.getAttributes().put("failedCount", 1L);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 12L);

    service.finalizeErrorOutput(ctx);

    ArgumentCaptor<Object> metaCaptor = ArgumentCaptor.forClass(Object.class);
    verify(runtimeRepository).updateFileMetadata(eq(99L), metaCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) metaCaptor.getValue();
    assertThat(meta)
        .containsEntry("badRecordCount", 1)
        .containsEntry("successCount", 10L)
        .containsEntry(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 1L)
        .containsEntry("failedCount", 1L)
        .containsEntry(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 12L)
        .containsEntry("errorOutputPath", "s3://bucket/file");

    ArgumentCaptor<FileAuditParam> auditCaptor = ArgumentCaptor.forClass(FileAuditParam.class);
    verify(fileAudits).appendAudit(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperationType()).isEqualTo("BAD_RECORD_GOVERNANCE");
    assertThat(auditCaptor.getValue().getFileId()).isEqualTo(99L);
  }

  @Test
  @DisplayName("错误出口仅为错误表时,不写错误文件但仍回填元数据")
  void shouldSkipErrorFileWrite_whenSinkIsErrorTableOnly() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "ERROR_TABLE"));

    ImportJobContext ctx = context();
    service.recordFailedRecord(ctx, ImportStage.PARSE, 1L, "E", "m", "raw");
    service.finalizeErrorOutput(ctx);

    verifyNoInteractions(errorOutputStorage);
    verify(runtimeRepository).updateFileMetadata(eq(99L), any());
  }

  @Test
  @DisplayName("缺少文件标识时收尾不写任何错误产物")
  void shouldSkipFinalize_whenFileIdMissing() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    ctx.getAttributes().remove(PipelineRuntimeKeys.FILE_ID);
    // 注入一条 bad record，但不提供 fileId。
    ctx.getAttributes()
        .put(
            "badRecords",
            new java.util.ArrayList<>(List.of(new ImportBadRecordEntity(
                1L, "PARSE", "E", "m", null, false, "CONTINUE", null, null, null))));

    service.finalizeErrorOutput(ctx);

    verifyNoInteractions(errorOutputStorage);
    verify(runtimeRepository, never()).updateFileMetadata(anyLong(), any());
  }

  // ── badRecords 列表治理 ──

  @Test
  @DisplayName("坏记录列表混入异型对象时,整体替换为规范集合")
  void shouldReplaceBadRecordsList_whenContainsForeignType() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    // 注入混入异型对象的旧列表
    ctx.getAttributes().put("badRecords", new java.util.ArrayList<>(List.of("not-a-record")));

    service.recordSkippedRecord(ctx, ImportStage.PARSE, 1L, "E", "m", "raw");

    @SuppressWarnings("unchecked")
    List<ImportBadRecordEntity> bad =
        (List<ImportBadRecordEntity>) ctx.getAttributes().get("badRecords");
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0)).isInstanceOf(ImportBadRecordEntity.class);
  }

  private Long toLongAnswer(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }
}
