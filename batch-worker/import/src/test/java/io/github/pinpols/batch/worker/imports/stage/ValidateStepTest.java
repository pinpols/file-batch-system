package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportDataQualityService;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import io.github.pinpols.batch.worker.imports.infrastructure.quality.ControlTotalEvaluator;
import io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationConfigSupport;
import io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationIssue;
import io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 单测：ValidateStep —— 关键路径覆盖。 主链路 happy path / 行级错误处理 / 数据集级错误 / 阈值超限 / JSON 反序列化失败 等。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("导入校验阶段单测:流式校验落盘,可跳过判定与阈值越界的失败语义")
class ValidateStepTest {

  @Mock
  private PlatformFileRecordRepository runtimeRepository;

  @Mock
  private ImportRecordGovernanceService governance;

  @Mock
  private ImportDataQualityService qualityService;

  private ImportWorkerConfiguration workerConfig;
  private ObjectMapper objectMapper;
  private ValidateStep step;

  @TempDir
  Path tempDir;

  private final List<Path> tempPaths = new ArrayList<>();

  @BeforeEach
  void setUp() {
    workerConfig = new ImportWorkerConfiguration(
        "wc",
        "wt",
        "tenant",
        5_000L,
        "topic",
        "cg",
        List.of(),
        new FileProcessing(true, 1000, 1000, 2),
        Boolean.FALSE);
    objectMapper = new ObjectMapper();
    ControlTotalEvaluator controlTotalEvaluator =
        new ControlTotalEvaluator(new ValidationConfigSupport(objectMapper));
    step = new ValidateStep(
        runtimeRepository,
        governance,
        qualityService,
        workerConfig,
        controlTotalEvaluator,
        objectMapper);
  }

  @AfterEach
  void cleanup() throws Exception {
    for (Path p : tempPaths) {
      Files.deleteIfExists(p);
    }
  }

  // ── stage() ──

  @Test
  @DisplayName("访问阶段属性时返回校验阶段标识")
  void shouldReturnValidateStage() {
    assertThat(step.stage()).isEqualTo(ImportStage.VALIDATE);
  }

  // ── input validation ──

  @Test
  @DisplayName("缺少已解析记录路径时失败,返回无数据流错误码")
  void shouldFail_whenParsedRecordsPathMissing() {
    ImportJobContext ctx = baseContext();

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_VALIDATE_NO_STREAM");
  }

  @Test
  @DisplayName("路径已声明但文件不存在时,同样返回无数据流错误码")
  void shouldFail_whenParsedRecordsFileMissing() {
    ImportJobContext ctx = baseContext();
    ctx.getAttributes()
        .put(
            PipelineRuntimeKeys.PARSED_RECORDS_PATH,
            tempDir.resolve("ghost.ndjson").toString());

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_VALIDATE_NO_STREAM");
  }

  // ── happy path ──

  @Test
  @DisplayName("全部行通过校验时写出校验后文件,计数与文件状态同步更新")
  void shouldStreamValidate_andWriteValidatedFile_whenAllRowsPass() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1"), row("C2"), row("C3")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 3L);

    ValidationSession session = session();
    when(qualityService.beginValidation(any(), eq(3L), any())).thenReturn(session);
    when(qualityService.validateChunkRows(any(), any(), anyLong())).thenReturn(Map.of());
    when(governance.withinThreshold(any())).thenReturn(true);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 3L);
    assertThat(ctx.getAttributes()).containsEntry("customerPayloadCount", 3L);
    assertThat(ctx.getAttributes()).containsKey(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH);
    // validated 文件存在且行数 == 3
    Path validated =
        Path.of((String) ctx.getAttributes().get(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH));
    tempPaths.add(validated);
    long count =
        Files.readAllLines(validated).stream().filter(l -> !l.isBlank()).count();
    assertThat(count).isEqualTo(3);
    verify(runtimeRepository).updateFileStatus(eq(99L), eq(FileStatus.VALIDATED.code()), any());
  }

  // ── dataset-level issue ──

  @Test
  @DisplayName("数据集级问题不可跳过时失败,并以该问题码作为错误码")
  void shouldFail_whenDatasetIssueNonSkippable() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());

    ValidationSession session =
        sessionWithDatasetIssues(List.of(new ValidationIssue(0L, "DATASET_BAD", "bad", null)));
    when(qualityService.beginValidation(any(), anyLong(), any())).thenReturn(session);
    when(governance.isSkippable("DATASET_BAD")).thenReturn(false);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DATASET_BAD");
    verify(governance)
        .recordFailedRecord(
            any(), eq(ImportStage.VALIDATE), eq(0L), eq("DATASET_BAD"), any(), any());
  }

  @Test
  @DisplayName("数据集级问题可跳过但超出阈值时,返回跳过超限失败")
  void shouldFail_whenDatasetIssueSkippableButThresholdExceeded() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());

    ValidationSession session =
        sessionWithDatasetIssues(List.of(new ValidationIssue(0L, "DS_SKIP", "skip", null)));
    when(qualityService.beginValidation(any(), anyLong(), any())).thenReturn(session);
    when(governance.isSkippable("DS_SKIP")).thenReturn(true);
    when(governance.shouldFailOnSkip("DS_SKIP")).thenReturn(false);
    when(governance.withinThreshold(any())).thenReturn(false);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_SKIP_THRESHOLD_EXCEEDED");
  }

  // ── row-level issue ──

  @Test
  @DisplayName("行级问题可跳过时,该行被跳过而整体仍成功")
  void shouldSkipBadRow_andStillProduceSuccess_whenRowSkippable() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 2L);

    ValidationSession session = session();
    when(qualityService.beginValidation(any(), anyLong(), any())).thenReturn(session);
    // recordNo 1 出错（可跳过），recordNo 2 通过
    when(qualityService.validateChunkRows(any(), any(), anyLong()))
        .thenReturn(Map.of(1L, new ValidationIssue(1L, "ROW_BAD", "bad", null)));
    when(governance.isSkippable("ROW_BAD")).thenReturn(true);
    when(governance.shouldFailOnSkip("ROW_BAD")).thenReturn(false);
    when(governance.withinThreshold(any())).thenReturn(true);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    // 只 1 行通过校验，被写到 validated 文件
    assertThat(ctx.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 1L);
    verify(governance)
        .recordSkippedRecord(any(), eq(ImportStage.VALIDATE), eq(1L), eq("ROW_BAD"), any(), any());
  }

  @Test
  @DisplayName("行级问题不可跳过时失败,并登记失败记录")
  void shouldFail_whenRowErrorNonSkippable() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 1L);

    ValidationSession session = session();
    when(qualityService.beginValidation(any(), anyLong(), any())).thenReturn(session);
    when(qualityService.validateChunkRows(any(), any(), anyLong()))
        .thenReturn(Map.of(1L, new ValidationIssue(1L, "HARD", "hard", null)));
    when(governance.isSkippable("HARD")).thenReturn(false);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("HARD");
    verify(governance)
        .recordFailedRecord(any(), eq(ImportStage.VALIDATE), eq(1L), eq("HARD"), any(), any());
  }

  // ── JSON parsing failure on a row ──

  @Test
  @DisplayName("非法结构行按类型非法计入校验错误并失败")
  void shouldHandleMalformedLine_andCountAsValidationError() throws Exception {
    Path parsed = Files.createTempFile("parsed-", ".ndjson");
    tempPaths.add(parsed);
    Files.writeString(parsed, "not-a-json\n", StandardCharsets.UTF_8);
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());

    ValidationSession session = session();
    when(qualityService.beginValidation(any(), anyLong(), any())).thenReturn(session);
    when(governance.isSkippable("IMPORT_VALIDATE_TYPE_INVALID")).thenReturn(false);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_VALIDATE_TYPE_INVALID");
    verify(governance)
        .recordFailedRecord(
            any(),
            eq(ImportStage.VALIDATE),
            eq(1L),
            eq("IMPORT_VALIDATE_TYPE_INVALID"),
            any(),
            any());
  }

  // ── post-loop threshold check ──

  @Test
  @DisplayName("所有分块处理完后阈值越界,整体判定失败")
  void shouldFail_whenWithinThresholdReturnsFalseAfterAllChunks() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 1L);

    ValidationSession session = session();
    when(qualityService.beginValidation(any(), anyLong(), any())).thenReturn(session);
    when(qualityService.validateChunkRows(any(), any(), anyLong())).thenReturn(Map.of());
    // post-loop 检查超阈值
    when(governance.withinThreshold(any())).thenReturn(false);

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_SKIP_THRESHOLD_EXCEEDED");
  }

  // ── exception in dataQualityService bubbles to IMPORT_VALIDATE_FAILED ──

  @Test
  @DisplayName("质量服务抛异常时返回通用失败结果")
  void shouldReturnGenericFailure_whenQualityServiceThrows() throws Exception {
    Path parsed = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());

    when(qualityService.beginValidation(any(), anyLong(), any()))
        .thenThrow(new RuntimeException("quality blew up"));

    ImportStageResult result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_VALIDATE_FAILED");
    verify(runtimeRepository, never()).updateFileStatus(anyLong(), any(), any());
  }

  // ── helpers ──

  private ImportJobContext baseContext() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("tenant-A");
    ctx.setJobCode("J");
    ctx.setWorkerId("w");
    ctx.setFileId("99");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    ctx.setAttributes(attrs);
    return ctx;
  }

  private Path writeNdjson(List<Map<String, Object>> rows) throws Exception {
    Path file = Files.createTempFile("parsed-", ".ndjson");
    tempPaths.add(file);
    StringBuilder sb = new StringBuilder();
    for (Map<String, Object> row : rows) {
      sb.append(objectMapper.writeValueAsString(row)).append('\n');
    }
    Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    return file;
  }

  private Map<String, Object> row(String cust) {
    Map<String, Object> m = new HashMap<>();
    m.put("customerNo", cust);
    return m;
  }

  private ValidationSession session() {
    return sessionWithDatasetIssues(List.of());
  }

  private ValidationSession sessionWithDatasetIssues(List<ValidationIssue> issues) {
    return new ValidationSession(
        null,
        Map.of(),
        0L,
        null,
        null,
        List.of(),
        new LinkedHashMap<>(),
        new ArrayList<>(issues),
        new LinkedHashMap<>(),
        new LinkedHashSet<>());
  }
}
