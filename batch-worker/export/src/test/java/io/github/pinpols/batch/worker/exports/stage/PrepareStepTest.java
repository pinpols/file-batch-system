package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportWorkerType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导出准备阶段单测:载荷解析,模板配置装载与命名规则及分片命名语义")
class PrepareStepTest {

  @Test
  @DisplayName("租户或原始载荷为空时返回准备阶段非法")
  void execute_returnsInvalid_whenTenantOrPayloadBlank() {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setRawPayload("");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_PREPARE_INVALID");
  }

  @Test
  @DisplayName("给出模板编码时:解析载荷,装载模板配置并回填文件名与对象名")
  void execute_parsesPayload_andLoadsTemplateConfig_whenTemplateCodeProvided() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig("t1", "TPL_1", ExportWorkerType.EXPORT))
        .thenReturn(Map.of(
            "file_format_type", "DELIMITED",
            "naming_rule", "exp_${bizDate}_${tenantId}_${batchNo}_${version}"));

    ExportPayload payload = new ExportPayload(
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
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE)))
        .isEqualTo("DELIMITED");
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.FILE_NAME)))
        .isEqualTo("exp_2026-03-25_t1_B001_v1");
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME)))
        .contains("outbound/");
    assertThat(ctx.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT))
        .isInstanceOf(Map.class);
  }

  @Test
  @DisplayName("上下文中已有导出载荷时直接复用,不再解析原始报文")
  void execute_usesExistingExportPayloadFromAttributes_withoutJsonParse() {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
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
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.FILE_NAME)))
        .isEqualTo("fixed.json");
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME)))
        .isEqualTo("obj.json");
  }

  @Test
  @DisplayName("载荷与上下文都缺业务日期时失败,并返回对应错误码")
  void execute_failsWhenBizDateMissingFromPayloadAndContext() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    ExportPayload payload = new ExportPayload(
        "FC1", "BIZ", null, "B001", null, null, null, null, Boolean.FALSE, null, Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_PREPARE_BIZ_DATE_MISSING");
  }

  @Test
  @DisplayName("多分片时文件名,对象名与临时对象名都追加且只追加一次分片后缀")
  void execute_addsPartitionSuffix_toAllNames_whenMultiPartition() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "DELIMITED"));

    ExportPayload payload = new ExportPayload(
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
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_NO, 2);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 4);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    // fileName / objectName / tempObjectName 三者均带且仅带一次分片后缀
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.FILE_NAME)))
        .contains("_p2of4.csv");
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME)))
        .contains("_p2of4.csv");
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.TEMP_OBJECT_NAME)))
        .contains("_p2of4");
  }

  @Test
  @DisplayName("未指定分片时按单片处理,文件名不带分片后缀")
  void execute_noPartitionSuffix_whenSinglePartition() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "DELIMITED"));

    ExportPayload payload = new ExportPayload(
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
    // 不设置 PARTITION_* → 默认单片 1/1,文件名应无后缀(向后兼容)

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.FILE_NAME)))
        .isEqualTo("BIZ_2026-03-25_B001.csv");
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.FILE_NAME)))
        .doesNotContain("_p");
  }

  @Test
  @DisplayName("载荷显式指定对象名时,多分片仍在其上追加分片后缀")
  void execute_tagsExplicitObjectName_whenMultiPartition() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformPipelineDefinitionRepository runtimeRepository =
        mock(PlatformPipelineDefinitionRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "JSON"));

    // payload 显式 objectName,走 resolveObjectName 的显式分支,需单独打标
    ExportPayload payload = new ExportPayload(
        "FC1",
        "BIZ",
        "TPL_1",
        "B001",
        null,
        "custom/report.json",
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
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_NO, 1);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 3);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(String.valueOf(ctx.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME)))
        .isEqualTo("custom/report_p1of3.json");
  }
}
