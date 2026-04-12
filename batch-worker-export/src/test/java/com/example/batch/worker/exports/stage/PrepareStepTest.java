package com.example.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportWorkerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrepareStepTest {

  @Test
  void execute_returnsInvalid_whenTenantOrPayloadBlank() {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setRawPayload("");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_PREPARE_INVALID");
  }

  @Test
  void execute_parsesPayload_andLoadsTemplateConfig_whenTemplateCodeProvided() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(
            eq("t1"), eq("TPL_1"), eq(ExportWorkerType.EXPORT)))
        .thenReturn(
            Map.of(
                "file_format_type", "DELIMITED",
                "naming_rule", "exp_${bizDate}_${tenantId}_${batchNo}_${version}"));

    ExportPayload payload =
        new ExportPayload(
            "FC1",
            "BIZ",
            "TPL_1",
            "B001",
            null,
            null,
            "2026-03-25",
            null,
            Boolean.FALSE,
            null,
            Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("exportPayload")).isInstanceOf(ExportPayload.class);
    assertThat(ctx.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG))
        .isInstanceOf(Map.class);
    assertThat(String.valueOf(ctx.getAttributes().get("exportFileFormatType")))
        .isEqualTo("DELIMITED");
    assertThat(String.valueOf(ctx.getAttributes().get("fileName")))
        .isEqualTo("exp_2026-03-25_t1_B001_v1");
    assertThat(String.valueOf(ctx.getAttributes().get("objectName"))).contains("outbound/");
    assertThat(ctx.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT))
        .isInstanceOf(Map.class);
  }

  @Test
  void execute_usesExistingExportPayloadFromAttributes_withoutJsonParse() {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "JSON"));

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload("{not-valid-json");
    ctx.getAttributes()
        .put(
            "exportPayload",
            new ExportPayload(
                "FC1",
                "BIZ",
                "TPL_1",
                "B001",
                "fixed.json",
                "obj.json",
                "2026-03-25",
                null,
                Boolean.FALSE,
                null,
                Map.of()));

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(String.valueOf(ctx.getAttributes().get("fileName"))).isEqualTo("fixed.json");
    assertThat(String.valueOf(ctx.getAttributes().get("objectName"))).isEqualTo("obj.json");
  }
}
