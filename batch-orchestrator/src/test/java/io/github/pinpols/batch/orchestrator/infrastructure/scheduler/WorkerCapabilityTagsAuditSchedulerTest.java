package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.config.WorkerCapabilityTagsAuditProperties;
import io.github.pinpols.batch.orchestrator.domain.param.InvalidCapabilityTagsParam;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("工作节点能力标签审计调度: 停机跳过, 指标归零与不合法标签判定口径")
class WorkerCapabilityTagsAuditSchedulerTest {

  private static final String METRIC = "batch.worker.capability_tags.invalid.count";

  @Mock
  private WorkerRegistryMapper workerRegistryMapper;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  private SimpleMeterRegistry meterRegistry;
  private WorkerCapabilityTagsAuditScheduler scheduler;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    WorkerCapabilityTagsAuditProperties auditProperties = new WorkerCapabilityTagsAuditProperties();
    auditProperties.setCapabilityTagsLogSampleLimit(10);
    scheduler = new WorkerCapabilityTagsAuditScheduler(
        workerRegistryMapper, gracefulShutdown, meterRegistry, auditProperties);
    scheduler.initializeMeters();
  }

  @Test
  @DisplayName("应用处于优雅停机状态时跳过本轮审计, 不查询不合法能力标签")
  void shouldSkipAudit_whenApplicationIsDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    scheduler.auditCapabilityTags();
    verify(workerRegistryMapper, never()).selectInvalidCapabilityTags();
  }

  @Test
  @DisplayName("审计结果为空时指标回落为零, 不残留上一轮计数")
  void shouldResetGaugeToZero_whenNoInvalidTagsFound() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags()).thenReturn(List.of());
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isZero();
  }

  @Test
  @DisplayName("能力标签为对象形态时判定为不合法, 计数加一")
  void shouldCountAsInvalid_whenTagsValueIsObjectForm() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(List.of(new InvalidCapabilityTagsParam("ta", "worker-1", "{\"ingest\":true}")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isEqualTo(1d);
  }

  @Test
  @DisplayName("标签数组含非字符串元素时判定为不合法, 计数加一")
  void shouldCountAsInvalid_whenArrayContainsNonStringElement() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(List.of(new InvalidCapabilityTagsParam("tb", "worker-2", "[\"ingest\", 42]")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isEqualTo(1d);
  }

  @Test
  @DisplayName("标签为合法字符串数组时不再计入, 指标保持为零")
  void shouldCountZero_whenTagsAreValidStringArray() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(
            List.of(new InvalidCapabilityTagsParam("ta", "worker-x", "[\"ingest\",\"delivery\"]")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isZero();
  }

  @Test
  @DisplayName("批量结果混合合法与不合法标签时, 只统计确认不合法的三条")
  void shouldCountOnlyConfirmedInvalid_whenBatchIsMixed() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(List.of(
            new InvalidCapabilityTagsParam("ta", "w-ok", "[\"ingest\"]"),
            new InvalidCapabilityTagsParam("ta", "w-obj", "{\"a\":1}"),
            new InvalidCapabilityTagsParam("tb", "w-scalar", "\"ingest\""),
            new InvalidCapabilityTagsParam("tb", "w-num-elem", "[\"a\", 1]")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isEqualTo(3d);
  }

  @Test
  @DisplayName("原始值为空引用或纯空白串时不判定为不合法, 指标保持为零")
  void shouldNotCountInvalid_whenRawValueIsNullOrBlank() {
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(workerRegistryMapper.selectInvalidCapabilityTags())
        .thenReturn(List.of(
            new InvalidCapabilityTagsParam("ta", "w-null", null),
            new InvalidCapabilityTagsParam("ta", "w-blank", "  ")));
    scheduler.auditCapabilityTags();
    assertThat(readGauge()).isZero();
  }

  private double readGauge() {
    Gauge g = meterRegistry.find(METRIC).gauge();
    assertThat(g).isNotNull();
    return g.value();
  }
}
