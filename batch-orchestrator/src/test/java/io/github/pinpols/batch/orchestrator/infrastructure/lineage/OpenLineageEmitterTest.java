package io.github.pinpols.batch.orchestrator.infrastructure.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.config.OpenLineageProperties;
import io.github.pinpols.batch.orchestrator.mapper.OpenLineageDatasetMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

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
    ObjectProvider<MeterRegistry> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<OpenLineageDatasetMapper> noDatasetMapper() {
    ObjectProvider<OpenLineageDatasetMapper> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);
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
  void disabled_emitIsNoOp() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(props(false, ""), noRegistry(), noDatasetMapper());
    // 不抛异常即可:disabled 时 executor=null,直接 return
    emitter.emitWorkflowTerminal(run("SUCCESS"), "SUCCESS", Instant.now());
  }

  @Test
  void buildRunEvent_successMapsToComplete() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(
            props(true, "http://localhost:5000/api/v1/lineage"), noRegistry(), noDatasetMapper());
    Instant finished = Instant.parse("2026-05-30T02:00:00Z");
    Map<String, Object> ev = emitter.buildRunEvent(run("SUCCESS"), "SUCCESS", finished);

    assertThat(ev.get("eventType")).isEqualTo("COMPLETE");
    assertThat(ev.get("eventTime")).isEqualTo("2026-05-30T02:00:00Z");
    assertThat(ev.get("producer")).isNotNull();
    assertThat(ev).containsKey("schemaURL");

    Map<?, ?> job = objectMap(ev.get("job"));
    assertThat(job.get("namespace")).isEqualTo("file-batch-system");
    assertThat(job.get("name")).isEqualTo("workflow.t1.def7");

    Map<?, ?> runNode = objectMap(ev.get("run"));
    assertThat(runNode.get("runId")).isEqualTo(OpenLineageEmitter.deterministicRunId(42L));
  }

  @Test
  void buildRunEvent_includesInputAndOutputDatasets() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(
            props(true, "http://localhost:5000/api/v1/lineage"), noRegistry(), noDatasetMapper());
    List<OpenLineageDatasetRow> datasets =
        List.of(
            dataset(11L, "INPUT", "S3", "raw", "in.csv", "/in.csv"),
            dataset(12L, "OUTPUT", "S3", "curated", "out.csv", "/out.csv"));

    Map<String, Object> ev =
        emitter.buildRunEvent(
            run("SUCCESS"), "SUCCESS", Instant.parse("2026-05-30T02:00:00Z"), datasets);

    List<?> inputs = (List<?>) ev.get("inputs");
    List<?> outputs = (List<?>) ev.get("outputs");
    assertThat(inputs).hasSize(1);
    assertThat(outputs).hasSize(1);
    Map<?, ?> input = objectMap(inputs.get(0));
    Map<?, ?> output = objectMap(outputs.get(0));
    assertThat(input.get("namespace")).isEqualTo("s3://raw");
    assertThat(input.get("name")).isEqualTo("/in.csv");
    assertThat(output.get("namespace")).isEqualTo("s3://curated");
    assertThat(output.get("name")).isEqualTo("/out.csv");

    Map<?, ?> facets = objectMap(output.get("facets"));
    Map<?, ?> bfsFile = objectMap(facets.get("bfsFile"));
    assertThat(bfsFile.get("fileId")).isEqualTo(12L);
    assertThat(bfsFile.get("fileCategory")).isEqualTo("OUTPUT");
    assertThat(bfsFile.get("fileSizeBytes")).isEqualTo(1024L);
  }

  @Test
  void buildRunEvent_treatsNonInputDatasetsAsOutputs() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(
            props(true, "http://localhost:5000/api/v1/lineage"), noRegistry(), noDatasetMapper());

    Map<String, Object> ev =
        emitter.buildRunEvent(
            run("SUCCESS"),
            "SUCCESS",
            Instant.parse("2026-05-30T02:00:00Z"),
            List.of(dataset(13L, "INTERMEDIATE", "LOCAL", null, "tmp.csv", null)));

    assertThat((List<?>) ev.get("inputs")).isEmpty();
    assertThat((List<?>) ev.get("outputs")).hasSize(1);
  }

  private static Map<?, ?> objectMap(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<?, ?>) value;
  }

  @Test
  void buildRunEvent_failedMapsToFail() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(
            props(true, "http://localhost:5000/api/v1/lineage"), noRegistry(), noDatasetMapper());
    Map<String, Object> ev =
        emitter.buildRunEvent(run("FAILED"), "FAILED", Instant.parse("2026-05-30T02:00:00Z"));
    assertThat(ev.get("eventType")).isEqualTo("FAIL");
  }

  @Test
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
        "GENERATED",
        "trace-abc");
  }
}
