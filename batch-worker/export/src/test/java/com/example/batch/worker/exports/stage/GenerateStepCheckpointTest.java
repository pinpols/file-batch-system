package com.example.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.config.WorkerCheckpointProperties;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import com.example.batch.worker.exports.stage.format.DelimitedExportFormat;
import com.example.batch.worker.exports.stage.format.ExcelExportFormat;
import com.example.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import com.example.batch.worker.exports.stage.format.FixedWidthExportFormat;
import com.example.batch.worker.exports.stage.format.GenerateCursorCodec;
import com.example.batch.worker.exports.stage.format.JsonExportFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-038 P3 端到端续跑测试:模拟 GENERATE 在第二页崩溃 → 位点记下首页 cursor → 重派从该 cursor 续写 → 最终文件完整有效。 验证「单文件 +
 * 字节位点截断」方案的核心正确性:不重复、不丢行、JSON 收尾后缀只写一次。
 */
class GenerateStepCheckpointTest {

  private static final String PLUGIN_ID = "test.export.plugin";
  private static final long INSTANCE_ID = 777_777L;
  private static final Map<String, Object> BATCH = Map.of("id", 1L, "batchCode", "B001");

  private ExportDataPlugin dataPlugin;
  private GenerateStep generateStep;
  private InMemoryPositionStore positionStore;
  private Path deterministicFile;

  /** 进程内位点存储:跨「崩溃」与「重派」两次 execute 复用同一实例,模拟 pipeline_progress 表的持久化。 */
  private static final class InMemoryPositionStore implements ProcessingPositionStore {
    private String marker;
    private long count;
    private boolean completed;
    int advanceCalls;

    @Override
    public ProcessingPosition load(String t, long id, ProcessingStage s) {
      if (completed) {
        return ProcessingPosition.completed(count);
      }
      return marker == null
          ? ProcessingPosition.empty()
          : new ProcessingPosition(marker, count, false);
    }

    @Override
    public void advance(String t, long id, ProcessingStage s, String newMarker, long inc) {
      this.marker = newMarker;
      this.count += inc;
      this.advanceCalls++;
    }

    @Override
    public void markCompleted(String t, long id, ProcessingStage s) {
      this.completed = true;
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    dataPlugin = mock(ExportDataPlugin.class);
    when(dataPlugin.id()).thenReturn(PLUGIN_ID);
    when(dataPlugin.loadBatch(any())).thenReturn(BATCH);

    ExportDataPluginRegistry pluginRegistry = mock(ExportDataPluginRegistry.class);
    when(pluginRegistry.require(any())).thenReturn(dataPlugin);

    ExportWorkerConfiguration config =
        new ExportWorkerConfiguration(
            "worker-test",
            "EXPORT",
            "tenant-test",
            5000L,
            "batch-export",
            "group-export",
            null,
            500_000L,
            new ExportWorkerConfiguration.FileProcessing(true, 100, 100, 50));

    ObjectMapper objectMapper = new ObjectMapper();
    ExportFormatStrategyRegistry formatStrategyRegistry =
        new ExportFormatStrategyRegistry(
            List.of(
                new JsonExportFormat(objectMapper),
                new DelimitedExportFormat(objectMapper),
                new ExcelExportFormat(objectMapper),
                new FixedWidthExportFormat(objectMapper)));

    positionStore = new InMemoryPositionStore();
    WorkerCheckpointProperties props = new WorkerCheckpointProperties();
    props.setEnabled(true); // 开续跑

    generateStep =
        new GenerateStep(
            pluginRegistry,
            formatStrategyRegistry,
            config,
            objectMapper,
            props,
            positionStore,
            new GenerateCursorCodec());

    deterministicFile =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "file-batch-export",
            "inst-" + INSTANCE_ID + ".json");
    Files.deleteIfExists(deterministicFile);
    Files.deleteIfExists(deterministicFile("DELIMITED"));
  }

  @AfterEach
  void tearDown() throws Exception {
    Files.deleteIfExists(deterministicFile);
    Files.deleteIfExists(deterministicFile("DELIMITED"));
  }

  @Test
  void crashOnSecondPage_thenResume_producesCompleteValidJsonWithoutDuplication() throws Exception {
    List<Map<String, Object>> page1 = List.of(Map.of("id", "1"), Map.of("id", "2"));
    List<Map<String, Object>> page2 = List.of(Map.of("id", "3"));

    // 首跑:首页正常,加载第二页时「崩溃」。
    when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null)))
        .thenReturn(new ExportDataPlugin.DetailPage(page1, "cursor-2"));
    when(dataPlugin.loadDetailPage(
            any(ExportDataContext.class), anyLong(), anyInt(), eq("cursor-2")))
        .thenThrow(new RuntimeException("simulated crash loading page 2"));

    ExportJobContext run1 = buildContext();
    ExportStageResult r1 = generateStep.execute(run1);

    // 崩溃 → 失败,但首页边界位点已落 + 残文件保留(未删)。
    assertThat(r1.success()).isFalse();
    assertThat(positionStore.advanceCalls).isEqualTo(1);
    assertThat(positionStore.marker).isNotNull();
    assertThat(deterministicFile).exists();
    long partialSize = Files.size(deterministicFile);
    assertThat(partialSize).isGreaterThan(0);

    // 重派:第二页这次能正常返回(null nextCursor 表示结束)。
    when(dataPlugin.loadDetailPage(
            any(ExportDataContext.class), anyLong(), anyInt(), eq("cursor-2")))
        .thenReturn(new ExportDataPlugin.DetailPage(page2, null));

    ExportJobContext run2 = buildContext();
    ExportStageResult r2 = generateStep.execute(run2);

    assertThat(r2.success()).isTrue();
    assertThat(run2.getAttributes().get("recordCount")).isEqualTo(3L);

    String content = Files.readString(deterministicFile);
    // 收尾后缀只在完成时写一次:整体是合法 JSON。
    Map<?, ?> parsed = new ObjectMapper().readValue(content, Map.class);
    List<?> details = (List<?>) parsed.get("details");
    assertThat(details).hasSize(3); // 不重复、不丢行
    assertThat(content).contains("\"1\"").contains("\"2\"").contains("\"3\"");
    // 续写从残文件末尾追加,文件应比首跑残文件更大。
    assertThat(Files.size(deterministicFile)).isGreaterThan(partialSize);
  }

  @Test
  void completedAndFileExists_isIdempotentlySkipped() throws Exception {
    // 先正常完整跑一遍(单页结束)。
    when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null)))
        .thenReturn(new ExportDataPlugin.DetailPage(List.of(Map.of("id", "1")), null));

    assertThat(generateStep.execute(buildContext()).success()).isTrue();
    int advancesAfterFirst = positionStore.advanceCalls;

    // 重派(已 completed + 文件在)→ 幂等跳过,不再 advance、不再读明细页。
    ExportJobContext rerun = buildContext();
    ExportStageResult result = generateStep.execute(rerun);

    assertThat(result.success()).isTrue();
    assertThat(rerun.getAttributes().get("recordCount")).isEqualTo(1L);
    assertThat(positionStore.advanceCalls).isEqualTo(advancesAfterFirst);
  }

  @Test
  void delimitedTrailerAfterResume_usesBatchControlTotal() throws Exception {
    when(dataPlugin.loadBatch(any()))
        .thenReturn(Map.of("id", 1L, "batchCode", "B001", "total_amount", "60.00"));
    List<Map<String, Object>> page1 =
        List.of(Map.of("id", "1", "amount", "10.00"), Map.of("id", "2", "amount", "20.00"));
    List<Map<String, Object>> page2 = List.of(Map.of("id", "3", "amount", "30.00"));

    when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null)))
        .thenReturn(new ExportDataPlugin.DetailPage(page1, "cursor-2"));
    when(dataPlugin.loadDetailPage(
            any(ExportDataContext.class), anyLong(), anyInt(), eq("cursor-2")))
        .thenThrow(new RuntimeException("simulated crash loading page 2"));

    assertThat(generateStep.execute(buildContext("DELIMITED", trailerTemplate())).success())
        .isFalse();
    assertThat(positionStore.marker).isNotNull();

    when(dataPlugin.loadDetailPage(
            any(ExportDataContext.class), anyLong(), anyInt(), eq("cursor-2")))
        .thenReturn(new ExportDataPlugin.DetailPage(page2, null));

    ExportJobContext rerun = buildContext("DELIMITED", trailerTemplate());
    ExportStageResult result = generateStep.execute(rerun);

    assertThat(result.success()).isTrue();
    List<String> lines = Files.readAllLines(deterministicFile("DELIMITED"));
    assertThat(lines).contains("T,3,60.00");
  }

  private ExportJobContext buildContext() {
    return buildContext("JSON", Map.of("export_data_ref", PLUGIN_ID));
  }

  private ExportJobContext buildContext(String fileFormatType, Map<String, Object> templateConfig) {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("tenant-gen-test");
    context.setJobCode("GEN_JOB");
    context.setWorkerId("worker-1");
    ExportPayload payload =
        new ExportPayload(
            null, null, "TMPL_001", "BATCH-001", null, null, null, null, null, null, Map.of());
    context.getAttributes().put("exportPayload", payload);
    context.getAttributes().put("exportFileFormatType", fileFormatType);
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, INSTANCE_ID);
    return context;
  }

  private Path deterministicFile(String fileFormatType) {
    String suffix = "DELIMITED".equalsIgnoreCase(fileFormatType) ? ".csv" : ".json";
    return Path.of(
        System.getProperty("java.io.tmpdir"), "file-batch-export", "inst-" + INSTANCE_ID + suffix);
  }

  private Map<String, Object> trailerTemplate() {
    return Map.of(
        "export_data_ref",
        PLUGIN_ID,
        "trailer_template",
        Map.of(
            "present",
            true,
            "recordType",
            "T",
            "recordTypeIndex",
            0,
            "recordCountIndex",
            1,
            "controlTotalIndex",
            2,
            "fieldCount",
            3,
            "amountField",
            "amount"));
  }
}
