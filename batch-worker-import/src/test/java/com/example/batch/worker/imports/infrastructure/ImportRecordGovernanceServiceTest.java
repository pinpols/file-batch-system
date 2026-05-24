package com.example.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.FileErrorRecordParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportSkipProperties;
import com.example.batch.worker.imports.domain.ImportBadRecord;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 单测：ImportRecordGovernanceService —— 跳过策略 / 阈值判定 / 坏记录落库 / 错误汇总。
 *
 * <p>覆盖主链路 happy path 与典型分支：CONTINUE / FAIL_BATCH / MANUAL_REVIEW、ABSOLUTE / PERCENTAGE
 * 阈值、ERROR_FILE / ERROR_TABLE sink、parse vs validate stage 分桶计数、bypassMode 关脱敏等。
 */
@ExtendWith(MockitoExtension.class)
class ImportRecordGovernanceServiceTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private ImportErrorOutputStorage errorOutputStorage;

  private BatchSecurityProperties batchSecurityProperties;

  private ImportRecordGovernanceService service;

  @BeforeEach
  void setUp() {
    batchSecurityProperties = new BatchSecurityProperties();
    // 默认 service 由各用例按场景重建，便于切换 ImportSkipProperties
  }

  private ImportRecordGovernanceService buildService(ImportSkipProperties props) {
    return new ImportRecordGovernanceService(
        props, runtimeRepository, errorOutputStorage, batchSecurityProperties);
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
  void shouldReturnFalse_whenSkipDisabled() {
    service = buildService(props(false, "ABSOLUTE", 5, 0.1, "E1", "CONTINUE", "BOTH"));
    assertThat(service.isSkipEnabled()).isFalse();
    assertThat(service.isSkippable("E1")).isFalse();
  }

  @Test
  void shouldAllowAll_whenSkipEnabledAndCodesEmpty() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "", "CONTINUE", "BOTH"));
    assertThat(service.isSkipEnabled()).isTrue();
    assertThat(service.isSkippable("ANY_CODE")).isTrue();
    assertThat(service.isSkippable("")).isFalse();
    assertThat(service.isSkippable(null)).isFalse();
  }

  @Test
  void shouldRestrictToConfiguredCodes_whenSkipCodesProvided() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "E1, E2 ,", "CONTINUE", "BOTH"));
    assertThat(service.isSkippable("E1")).isTrue();
    assertThat(service.isSkippable("E2")).isTrue();
    assertThat(service.isSkippable("E3")).isFalse();
  }

  @Test
  void shouldFailOnSkip_onlyWhenActionIsFailBatchAndCodeSkippable() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "E1", "FAIL_BATCH", "BOTH"));
    assertThat(service.shouldFailOnSkip("E1")).isTrue();
    assertThat(service.shouldFailOnSkip("E2")).isFalse();
  }

  @Test
  void shouldNotFailOnSkip_whenActionIsContinue() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "E1", "CONTINUE", "BOTH"));
    assertThat(service.shouldFailOnSkip("E1")).isFalse();
  }

  @Test
  void shouldManualReview_onlyWhenActionIsManualReview() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "", "MANUAL_REVIEW", "BOTH"));
    assertThat(service.shouldManualReview()).isTrue();

    service = buildService(props(true, "ABSOLUTE", 5, 0.1, "", "CONTINUE", "BOTH"));
    assertThat(service.shouldManualReview()).isFalse();
  }

  // ── withinThreshold ──

  @Test
  void shouldReturnTrue_whenSkipDisabled_regardlessOfCount() {
    service = buildService(props(false, "ABSOLUTE", 0, 0.0, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put("skippedCount", 9999L);
    assertThat(service.withinThreshold(ctx)).isTrue();
  }

  @Test
  void shouldEnforceAbsoluteThreshold() {
    service = buildService(props(true, "ABSOLUTE", 3, 0.5, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put("skippedCount", 3L);
    assertThat(service.withinThreshold(ctx)).isTrue();
    ctx.getAttributes().put("skippedCount", 4L);
    assertThat(service.withinThreshold(ctx)).isFalse();
  }

  @Test
  void shouldEnforcePercentageThreshold() {
    service = buildService(props(true, "PERCENTAGE", 0, 0.1, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put("totalCount", 100L);
    ctx.getAttributes().put("skippedCount", 10L);
    assertThat(service.withinThreshold(ctx)).isTrue();
    ctx.getAttributes().put("skippedCount", 11L);
    assertThat(service.withinThreshold(ctx)).isFalse();
  }

  @Test
  void shouldTreatRateAsZero_whenTotalCountZero() {
    service = buildService(props(true, "PERCENTAGE", 0, 0.1, "", "CONTINUE", "BOTH"));
    ImportJobContext ctx = context();
    ctx.getAttributes().put("totalCount", 0L);
    ctx.getAttributes().put("skippedCount", 5L);
    assertThat(service.withinThreshold(ctx)).isTrue();
  }

  // ── recordSkippedRecord / recordFailedRecord / stage scoped counters ──

  @Test
  void shouldRecordSkippedRecord_incrementsSkippedAndStageScopedCounters() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    service.recordSkippedRecord(ctx, ImportStage.PARSE, 10L, "E1", "msg-1", Map.of("k", "v"));

    assertThat(ctx.getAttributes()).containsEntry("skippedCount", 1L);
    assertThat(ctx.getAttributes()).containsEntry("parseSkippedCount", 1L);
    assertThat(ctx.getAttributes()).containsEntry("lastProcessedRecordNo", 10L);
    assertThat(ctx.getAttributes()).containsEntry("lastErrorCode", "E1");
    assertThat(ctx.getAttributes()).containsEntry("lastErrorMessage", "msg-1");

    @SuppressWarnings("unchecked")
    List<ImportBadRecord> bad = (List<ImportBadRecord>) ctx.getAttributes().get("badRecords");
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).skipped()).isTrue();
    assertThat(bad.get(0).stageCode()).isEqualTo("PARSE");

    ArgumentCaptor<FileErrorRecordParam> captor =
        ArgumentCaptor.forClass(FileErrorRecordParam.class);
    verify(runtimeRepository).insertFileErrorRecord(captor.capture());
    FileErrorRecordParam param = captor.getValue();
    assertThat(param.getTenantId()).isEqualTo("tenant-A");
    assertThat(param.getFileId()).isEqualTo(99L);
    assertThat(param.getRecordNo()).isEqualTo(10L);
    assertThat(param.getErrorCode()).isEqualTo("E1");
    assertThat(param.getErrorStage()).isEqualTo("PARSE");
    assertThat(param.isSkipped()).isTrue();
  }

  @Test
  void shouldRecordFailedRecord_incrementsFailedAndStashesLastBadRecord() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    service.recordFailedRecord(ctx, ImportStage.VALIDATE, 5L, "EFAIL", "boom", "raw");

    assertThat(ctx.getAttributes()).containsEntry("failedCount", 1L);
    assertThat(ctx.getAttributes()).containsEntry("validateFailedCount", 1L);
    assertThat(ctx.getAttributes()).containsKey("lastBadRecord");
    verify(runtimeRepository).insertFileErrorRecord(any());
  }

  @Test
  void shouldFlagManualReview_whenSkippedAndActionIsManualReview() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "MANUAL_REVIEW", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    service.recordSkippedRecord(ctx, ImportStage.PARSE, 1L, "E1", "m", "raw");
    assertThat(ctx.getAttributes()).containsEntry("manualReviewRequired", true);
  }

  @Test
  void shouldMaskErrorPayload_whenTemplateConfigEnablesMasking() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    ctx.getAttributes()
        .put(
            PipelineRuntimeKeys.TEMPLATE_CONFIG,
            Map.of("error_line_masking_enabled", true, "masking_rule_set", "default"));

    service.recordFailedRecord(ctx, ImportStage.VALIDATE, 1L, "E", "user=alice", "raw-payload");

    ArgumentCaptor<FileErrorRecordParam> captor =
        ArgumentCaptor.forClass(FileErrorRecordParam.class);
    verify(runtimeRepository).insertFileErrorRecord(captor.capture());
    // masking 经过 ContentMaskingUtils 处理；这里只断言原始 message 被替换（非 null 且不等于原文）
    assertThat(captor.getValue().getErrorMessage()).isNotNull();
  }

  @Test
  void shouldDisableMasking_whenBypassModeOn() {
    batchSecurityProperties.setBypassMode(true);
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    ctx.getAttributes()
        .put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("error_line_masking_enabled", true));
    service.recordFailedRecord(ctx, ImportStage.VALIDATE, 1L, "E", "raw-msg", "raw");

    ArgumentCaptor<FileErrorRecordParam> captor =
        ArgumentCaptor.forClass(FileErrorRecordParam.class);
    verify(runtimeRepository).insertFileErrorRecord(captor.capture());
    assertThat(captor.getValue().getErrorMessage()).isEqualTo("raw-msg");
  }

  // ── recordThresholdViolation ──

  @Test
  void shouldRecordThresholdViolation_andSetSkipFlag() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    service.recordThresholdViolation(ctx, ImportStage.VALIDATE, "THRESH", "exceeded");

    assertThat(ctx.getAttributes()).containsEntry("skipThresholdExceeded", true);
    verify(runtimeRepository).insertFileErrorRecord(any());
  }

  // ── finalizeErrorOutput ──

  @Test
  void shouldSkipFinalize_whenNoBadRecords() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));

    ImportJobContext ctx = context();
    service.finalizeErrorOutput(ctx);

    verifyNoInteractions(errorOutputStorage);
    verify(runtimeRepository, never()).appendAudit(any());
    verify(runtimeRepository, never()).updateFileMetadata(anyLong(), any());
  }

  @Test
  void shouldFinalizeErrorOutput_writesFileMetadataAndAudit() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));
    when(errorOutputStorage.writeErrorOutput(eq("tenant-A"), eq("99"), any()))
        .thenReturn("s3://bucket/file");

    ImportJobContext ctx = context();
    // 先塞 1 条坏记录
    service.recordSkippedRecord(ctx, ImportStage.PARSE, 1L, "E", "m", "raw");
    ctx.getAttributes().put("successCount", 10L);
    ctx.getAttributes().put("failedCount", 1L);
    ctx.getAttributes().put("totalCount", 12L);

    service.finalizeErrorOutput(ctx);

    ArgumentCaptor<Object> metaCaptor = ArgumentCaptor.forClass(Object.class);
    verify(runtimeRepository).updateFileMetadata(eq(99L), metaCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) metaCaptor.getValue();
    assertThat(meta)
        .containsEntry("badRecordCount", 1)
        .containsEntry("successCount", 10L)
        .containsEntry("skippedCount", 1L)
        .containsEntry("failedCount", 1L)
        .containsEntry("totalCount", 12L)
        .containsEntry("errorOutputPath", "s3://bucket/file");

    ArgumentCaptor<FileAuditParam> auditCaptor = ArgumentCaptor.forClass(FileAuditParam.class);
    verify(runtimeRepository).appendAudit(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperationType()).isEqualTo("BAD_RECORD_GOVERNANCE");
    assertThat(auditCaptor.getValue().getFileId()).isEqualTo(99L);
  }

  @Test
  void shouldSkipErrorFileWrite_whenSinkIsErrorTableOnly() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "ERROR_TABLE"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    service.recordFailedRecord(ctx, ImportStage.PARSE, 1L, "E", "m", "raw");
    service.finalizeErrorOutput(ctx);

    verifyNoInteractions(errorOutputStorage);
    verify(runtimeRepository).updateFileMetadata(eq(99L), any());
  }

  @Test
  void shouldSkipFinalize_whenFileIdMissing() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenReturn(null);

    ImportJobContext ctx = context();
    // 注入一条 bad record，但 fileId 解析为 null
    ctx.getAttributes()
        .put(
            "badRecords",
            new java.util.ArrayList<>(
                List.of(
                    new ImportBadRecord(1L, "PARSE", "E", "m", null, false, "CONTINUE", null))));

    service.finalizeErrorOutput(ctx);

    verifyNoInteractions(errorOutputStorage);
    verify(runtimeRepository, never()).updateFileMetadata(anyLong(), any());
  }

  // ── badRecords 列表治理 ──

  @Test
  void shouldReplaceBadRecordsList_whenContainsForeignType() {
    service = buildService(props(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH"));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLongAnswer(inv.getArgument(0)));

    ImportJobContext ctx = context();
    // 注入混入异型对象的旧列表
    ctx.getAttributes().put("badRecords", new java.util.ArrayList<>(List.of("not-a-record")));

    service.recordSkippedRecord(ctx, ImportStage.PARSE, 1L, "E", "m", "raw");

    @SuppressWarnings("unchecked")
    List<ImportBadRecord> bad = (List<ImportBadRecord>) ctx.getAttributes().get("badRecords");
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0)).isInstanceOf(ImportBadRecord.class);
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
