package io.github.pinpols.batch.worker.core.infrastructure.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.domain.PipelineProgressEntity;
import io.github.pinpols.batch.worker.core.mapper.PipelineProgressMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DefaultProcessingPositionStoreTest {

  private static final String TENANT = "t1";
  private static final long INSTANCE = 1001L;

  @Mock PipelineProgressMapper mapper;
  @Mock ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

  private SimpleMeterRegistry meterRegistry;
  private DefaultProcessingPositionStore store;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
    store = new DefaultProcessingPositionStore(mapper, meterRegistryProvider);
  }

  @Test
  @DisplayName("load:无行返回 empty(),首次跑场景")
  void shouldReturnEmpty_whenNoRow() {
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "LOAD")).thenReturn(null);

    ProcessingPosition pos = store.load(TENANT, INSTANCE, ProcessingStage.LOAD);

    assertThat(pos).isEqualTo(ProcessingPosition.empty());
    assertThat(pos.completed()).isFalse();
    assertThat(pos.positionMarker()).isNull();
    assertThat(pos.processedCount()).isZero();
    assertMetric("LOAD", "load", "empty", 1.0);
  }

  @Test
  @DisplayName("load:completed=true 行返回 completed 标记,marker 字段不再有意义")
  void shouldReturnCompleted_whenRowCompleted() {
    PipelineProgressEntity row =
        new PipelineProgressEntity(
            1L,
            TENANT,
            INSTANCE,
            "GENERATE",
            "stale-marker",
            999L,
            true,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            OffsetDateTime.now());
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "GENERATE")).thenReturn(row);

    ProcessingPosition pos = store.load(TENANT, INSTANCE, ProcessingStage.GENERATE);

    assertThat(pos.completed()).isTrue();
    assertThat(pos.processedCount()).isEqualTo(999L);
    assertThat(pos.positionMarker()).isNull(); // completed() 不带 marker
    assertMetric("GENERATE", "load", "completed", 1.0);
  }

  @Test
  @DisplayName("load:未完成行如实返回 marker + count")
  void shouldReturnInProgress_whenRowNotCompleted() {
    PipelineProgressEntity row =
        new PipelineProgressEntity(
            2L,
            TENANT,
            INSTANCE,
            "LOAD",
            "row:12345",
            12345L,
            false,
            null,
            OffsetDateTime.now(),
            OffsetDateTime.now());
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "LOAD")).thenReturn(row);

    ProcessingPosition pos = store.load(TENANT, INSTANCE, ProcessingStage.LOAD);

    assertThat(pos.completed()).isFalse();
    assertThat(pos.positionMarker()).isEqualTo("row:12345");
    assertThat(pos.processedCount()).isEqualTo(12345L);
    assertMetric("LOAD", "load", "resumable", 1.0);
    // 命中续跑 → 跳过的记录数(=已处理 count)进入 resume.skipped counter
    assertResumeSkipped("LOAD", 12345.0);
  }

  @Test
  @DisplayName("resume skipped:命中续跑但 processedCount=0(位点在但无已提交记录)不增 skipped counter")
  void shouldNotRecordResumeSkipped_whenProcessedCountZero() {
    PipelineProgressEntity row =
        new PipelineProgressEntity(
            3L, TENANT, INSTANCE, "LOAD", "row:0", 0L, false, null, OffsetDateTime.now(), null);
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "LOAD")).thenReturn(row);

    store.load(TENANT, INSTANCE, ProcessingStage.LOAD);

    // 命中次数仍计,但跳过记录数为 0 时不注册 skipped counter(避免 0 值噪声)
    assertMetric("LOAD", "load", "resumable", 1.0);
    assertThat(meterRegistry.find(DefaultProcessingPositionStore.METRIC_RESUME_SKIPPED).counter())
        .isNull();
  }

  @Test
  @DisplayName("advance:透传到 mapper,使用 stage.code() 作为 DB 列值")
  void shouldDelegateAdvance() {
    store.advance(TENANT, INSTANCE, ProcessingStage.LOAD, "row:500", 500L);

    verify(mapper).advance(eq(TENANT), eq(INSTANCE), eq("LOAD"), eq("row:500"), eq(500L));
    assertMetric("LOAD", "advance", "success", 1.0);
  }

  @Test
  @DisplayName("markCompleted:透传到 mapper")
  void shouldDelegateMarkCompleted() {
    store.markCompleted(TENANT, INSTANCE, ProcessingStage.GENERATE);

    verify(mapper).markCompleted(eq(TENANT), eq(INSTANCE), eq("GENERATE"));
    assertMetric("GENERATE", "complete", "success", 1.0);
  }

  @Test
  @DisplayName("存储异常:记录 failure 指标并保留原异常")
  void shouldRecordFailureAndRethrow() {
    IllegalStateException failure = new IllegalStateException("database unavailable");
    doThrow(failure).when(mapper).advance(TENANT, INSTANCE, "LOAD", "row:500", 500L);

    assertThatThrownBy(() -> store.advance(TENANT, INSTANCE, ProcessingStage.LOAD, "row:500", 500L))
        .isSameAs(failure);

    assertMetric("LOAD", "advance", "failure", 1.0);
  }

  @Test
  @DisplayName("load 是只读路径,不会触发 advance / markCompleted")
  void loadShouldNotMutate() {
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "LOAD")).thenReturn(null);

    store.load(TENANT, INSTANCE, ProcessingStage.LOAD);

    // findByInstanceAndStage 触发(by when()),advance / markCompleted 不触发
    verify(mapper).findByInstanceAndStage(TENANT, INSTANCE, "LOAD");
  }

  private void assertResumeSkipped(String stage, double expected) {
    assertThat(
            meterRegistry
                .get(DefaultProcessingPositionStore.METRIC_RESUME_SKIPPED)
                .tags("stage", stage)
                .counter()
                .count())
        .isEqualTo(expected);
  }

  private void assertMetric(String stage, String operation, String outcome, double expected) {
    assertThat(
            meterRegistry
                .get(DefaultProcessingPositionStore.METRIC_OPERATIONS)
                .tags("stage", stage, "operation", operation, "outcome", outcome)
                .counter()
                .count())
        .isEqualTo(expected);
  }
}
