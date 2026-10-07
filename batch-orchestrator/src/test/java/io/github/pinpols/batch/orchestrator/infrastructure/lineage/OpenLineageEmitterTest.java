package io.github.pinpols.batch.orchestrator.infrastructure.lineage;

import static io.github.pinpols.batch.testing.TestHttpTransports.failOnRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.config.OpenLineageProperties;
import io.github.pinpols.batch.orchestrator.mapper.OpenLineageDatasetMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("血缘事件生成器: 运行态事件类型与时间映射, 输入输出数据集归类及文件附加信息")
class OpenLineageEmitterTest {

  private OpenLineageProperties props(boolean enabled, String endpoint) {
    OpenLineageProperties p = new OpenLineageProperties();
    p.setEnabled(enabled);
    p.setEndpoint(endpoint);
    p.setNamespace("file-batch-system");
    return p;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<MeterRegistry> noRegistry() {
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<OpenLineageDatasetMapper> noDatasetMapper() {
    ObjectProvider<OpenLineageDatasetMapper> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  private WorkflowRunEntity run(String status) {
    WorkflowRunEntity e = new WorkflowRunEntity();
    e.setId(42L);
    e.setTenantId("t1");
    e.setWorkflowDefinitionId(7L);
    e.setRelatedJobInstanceId(101L);
    e.setBizDate(LocalDate.parse("2026-05-30"));
    e.setTraceId("trace-abc");
    e.setStartedAt(Instant.parse("2026-05-30T01:00:00Z"));
    e.setRunStatus(status);
    return e;
  }

  @Test
  @DisplayName("成功结束时生成完成态运行事件, 事件时间取结束时刻, 作业与运行标识按租户与定义映射")
  void buildRunEvent_successMapsToComplete() {
    OpenLineageEmitter emitter = new OpenLineageEmitter(
        props(true, "http://localhost:5000/api/v1/lineage"),
        noRegistry(),
        noDatasetMapper(),
        failOnRequest());
    Instant finished = Instant.parse("2026-05-30T02:00:00Z");
    Map<String, Object> ev = emitter.buildRunEvent(run("SUCCESS"), "SUCCESS", finished);

    assertThat(ev).containsEntry("eventType", "COMPLETE");
    assertThat(ev).containsEntry("eventTime", "2026-05-30T02:00:00Z");
    assertThat(ev.get("producer")).isNotNull();
    assertThat(ev).containsKey("schemaURL");

    Map<String, Object> job = objectMap(ev.get("job"));
    assertThat(job).containsEntry("namespace", "file-batch-system");
    assertThat(job).containsEntry("name", "workflow.t1.def7");

    Map<String, Object> runNode = objectMap(ev.get("run"));
    assertThat(runNode).containsEntry("runId", OpenLineageEmitter.deterministicRunId(42L));
  }

  @Test
  @DisplayName("按文件流向归类数据集: 输入进入输入集合, 输出携带文件标识, 类别与字节数附加信息")
  void buildRunEvent_includesInputAndOutputDatasets() {
    OpenLineageEmitter emitter = new OpenLineageEmitter(
        props(true, "http://localhost:5000/api/v1/lineage"),
        noRegistry(),
        noDatasetMapper(),
        failOnRequest());
    List<OpenLineageDatasetRow> datasets = List.of(
        dataset(11L, "INPUT", "S3", "raw", "in.csv", "/in.csv"),
        dataset(12L, "OUTPUT", "S3", "curated", "out.csv", "/out.csv"));

    Map<String, Object> ev = emitter.buildRunEvent(
        run("SUCCESS"), "SUCCESS", Instant.parse("2026-05-30T02:00:00Z"), datasets);

    List<?> inputs = (List<?>) ev.get("inputs");
    List<?> outputs = (List<?>) ev.get("outputs");
    assertThat(inputs).hasSize(1);
    assertThat(outputs).hasSize(1);
    Map<String, Object> input = objectMap(inputs.get(0));
    Map<String, Object> output = objectMap(outputs.get(0));
    assertThat(input).containsEntry("namespace", "s3://raw");
    assertThat(input).containsEntry("name", "/in.csv");
    assertThat(output).containsEntry("namespace", "s3://curated");
    assertThat(output).containsEntry("name", "/out.csv");

    Map<String, Object> facets = objectMap(output.get("facets"));
    Map<String, Object> bfsFile = objectMap(facets.get("bfsFile"));
    assertThat(bfsFile).containsEntry("fileId", 12L);
    assertThat(bfsFile).containsEntry("fileCategory", "OUTPUT");
    assertThat(bfsFile).containsEntry("fileSizeBytes", 1024L);
  }

  @Test
  @DisplayName("非输入类别的数据集只出现在输出集合, 输入集合保持为空")
  void buildRunEvent_treatsNonInputDatasetsAsOutputs() {
    OpenLineageEmitter emitter = new OpenLineageEmitter(
        props(true, "http://localhost:5000/api/v1/lineage"),
        noRegistry(),
        noDatasetMapper(),
        failOnRequest());

    Map<String, Object> ev = emitter.buildRunEvent(
        run("SUCCESS"),
        "SUCCESS",
        Instant.parse("2026-05-30T02:00:00Z"),
        List.of(dataset(13L, "INTERMEDIATE", "LOCAL", null, "tmp.csv", null)));

    assertThat((List<?>) ev.get("inputs")).isEmpty();
    assertThat((List<?>) ev.get("outputs")).hasSize(1);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @Test
  @DisplayName("失败结束时生成失败态运行事件")
  void buildRunEvent_failedMapsToFail() {
    OpenLineageEmitter emitter = new OpenLineageEmitter(
        props(true, "http://localhost:5000/api/v1/lineage"),
        noRegistry(),
        noDatasetMapper(),
        failOnRequest());
    Map<String, Object> ev =
        emitter.buildRunEvent(run("FAILED"), "FAILED", Instant.parse("2026-05-30T02:00:00Z"));
    assertThat(ev).containsEntry("eventType", "FAIL");
  }

  @Test
  @DisplayName("同一运行标识重复计算结果一致, 且符合通用唯一标识的文本格式")
  void deterministicRunId_isStableAndUuid() {
    String a = OpenLineageEmitter.deterministicRunId(42L);
    String b = OpenLineageEmitter.deterministicRunId(42L);
    assertThat(a).isEqualTo(b);
    assertThat(java.util.UUID.fromString(a)).isNotNull();
  }

  private OpenLineageDatasetRow dataset(
      Long fileId,
      String category,
      String storageType,
      String bucket,
      String fileName,
      String storagePath) {
    return new OpenLineageDatasetRow(
        fileId,
        "t1",
        category,
        fileName,
        "DELIMITED",
        1024L,
        "SHA-256",
        "abc",
        storageType,
        bucket,
        storagePath,
        FileStatus.GENERATED.code(),
        "trace-abc");
  }
}
