package com.example.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Export COMPLETE 阶段单测：
 *
 * <ul>
 *   <li>autoDispatch=true → nextStatus = DISPATCHING
 *   <li>autoDispatch=false / 无 payload → nextStatus = GENERATED
 *   <li>objectName 缺失 → 失败 EXPORT_COMPLETE_INVALID
 *   <li>dry-run → 跳过 status / audit
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CompleteStepTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @InjectMocks private CompleteStep step;

  private static ExportJobContext baseContext() {
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t-1");
    ctx.setWorkerId("worker-X");
    ctx.getAttributes().put(PipelineRuntimeKeys.FILE_ID, "501");
    ctx.getAttributes().put("recordCount", 1234L);
    ctx.getAttributes().put("objectName", "outbound/2026-05-21/x.json");
    ctx.getAttributes().put("fileSizeBytes", 4096L);
    ctx.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "trace-abc");
    return ctx;
  }

  @Test
  @DisplayName("autoDispatch=TRUE → 文件状态推进到 DISPATCHING，audit 写入 EXPORT_COMPLETE")
  void shouldTransitionToDispatching_whenAutoDispatchEnabled() {
    ExportJobContext ctx = baseContext();
    ExportPayload payload =
        new ExportPayload(
            "FC1", "BIZ", "TPL", "B1", "f.json", "obj.json", "2026-05-21",
            null, Boolean.TRUE, null, Map.of());
    ctx.getAttributes().put("exportPayload", payload);
    when(runtimeRepository.toLong(any())).thenReturn(501L);

    ExportStageResult result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(result.stage()).isEqualTo(ExportStage.COMPLETE);
    verify(runtimeRepository).updateFileStatus(eq(501L), eq("DISPATCHING"), any());
    ArgumentCaptor<FileAuditParam> auditCaptor = ArgumentCaptor.forClass(FileAuditParam.class);
    verify(runtimeRepository).appendAudit(auditCaptor.capture());
    FileAuditParam audit = auditCaptor.getValue();
    assertThat(audit.getOperationType()).isEqualTo("EXPORT_COMPLETE");
    assertThat(audit.getOperationResult()).isEqualTo("SUCCESS");
    assertThat(audit.getTraceId()).isEqualTo("trace-abc");
    assertThat(audit.getEvidenceRef()).isEqualTo("outbound/2026-05-21/x.json");
  }

  @Test
  @DisplayName("autoDispatch=FALSE → 文件状态推进到 GENERATED")
  void shouldTransitionToGenerated_whenAutoDispatchFalse() {
    ExportJobContext ctx = baseContext();
    ExportPayload payload =
        new ExportPayload(
            "FC1", "BIZ", "TPL", "B1", "f.json", "obj.json", "2026-05-21",
            null, Boolean.FALSE, null, Map.of());
    ctx.getAttributes().put("exportPayload", payload);
    when(runtimeRepository.toLong(any())).thenReturn(501L);

    ExportStageResult result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(runtimeRepository).updateFileStatus(eq(501L), eq("GENERATED"), any());
  }

  @Test
  @DisplayName("无 exportPayload → 默认 GENERATED")
  void shouldDefaultToGenerated_whenNoPayloadInAttributes() {
    ExportJobContext ctx = baseContext();
    when(runtimeRepository.toLong(any())).thenReturn(501L);

    ExportStageResult result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(runtimeRepository).updateFileStatus(eq(501L), eq("GENERATED"), any());
  }

  @Test
  @DisplayName("objectName 缺失 → 失败 EXPORT_COMPLETE_INVALID，不调任何 repository 写")
  void shouldReturnFailure_whenObjectNameMissing() {
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t-1");
    // 不放 objectName

    ExportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_COMPLETE_INVALID");
    verify(runtimeRepository, never()).updateFileStatus(any(), any(), any());
    verify(runtimeRepository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("null context → 失败 EXPORT_COMPLETE_INVALID")
  void shouldReturnFailure_whenContextIsNull() {
    ExportStageResult result = step.execute(null);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_COMPLETE_INVALID");
    verifyNoInteractions(runtimeRepository);
  }

  @Test
  @DisplayName("dry-run 模式 → 直接 success，完全跳过 status / audit")
  void shouldSkipAllSideEffects_whenDryRun() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put("dryRun", Boolean.TRUE);

    ExportStageResult result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    verifyNoInteractions(runtimeRepository);
  }

  @Test
  @DisplayName("EXPORT_SNAPSHOT 存在 → 透传到 fileMetadata")
  void shouldIncludeExportSnapshotInMetadata_whenPresent() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.EXPORT_SNAPSHOT, Map.of("snap", "v1"));
    when(runtimeRepository.toLong(any())).thenReturn(501L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    step.execute(ctx);

    verify(runtimeRepository).updateFileStatus(eq(501L), eq("GENERATED"), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).containsKey("exportSnapshot");
  }
}
