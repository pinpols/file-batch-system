package io.github.pinpols.batch.orchestrator.infrastructure.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.config.OpenLineageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
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

  private WorkflowRunEntity run(String status) {
    WorkflowRunEntity e = new WorkflowRunEntity();
    e.setId(42L);
    e.setTenantId("t1");
    e.setWorkflowDefinitionId(7L);
    e.setBizDate(LocalDate.parse("2026-05-30"));
    e.setTraceId("trace-abc");
    e.setStartedAt(Instant.parse("2026-05-30T01:00:00Z"));
    e.setRunStatus(status);
    return e;
  }

  @Test
  void disabled_emitIsNoOp() {
    OpenLineageEmitter emitter = new OpenLineageEmitter(props(false, ""), noRegistry());
    // 不抛异常即可:disabled 时 executor=null,直接 return
    emitter.emitWorkflowTerminal(run("SUCCESS"), "SUCCESS", Instant.now());
  }

  @Test
  void buildRunEvent_successMapsToComplete() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(props(true, "http://localhost:5000/api/v1/lineage"), noRegistry());
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

  private static Map<?, ?> objectMap(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<?, ?>) value;
  }

  @Test
  void buildRunEvent_failedMapsToFail() {
    OpenLineageEmitter emitter =
        new OpenLineageEmitter(props(true, "http://localhost:5000/api/v1/lineage"), noRegistry());
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
}
