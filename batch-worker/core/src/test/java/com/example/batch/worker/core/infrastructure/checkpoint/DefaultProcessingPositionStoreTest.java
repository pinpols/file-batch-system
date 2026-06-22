package com.example.batch.worker.core.infrastructure.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.domain.PipelineProgressEntity;
import com.example.batch.worker.core.mapper.PipelineProgressMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultProcessingPositionStoreTest {

  private static final String TENANT = "t1";
  private static final long INSTANCE = 1001L;

  @Mock PipelineProgressMapper mapper;

  @InjectMocks DefaultProcessingPositionStore store;

  @Test
  @DisplayName("load:无行返回 empty(),首次跑场景")
  void shouldReturnEmpty_whenNoRow() {
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "LOAD")).thenReturn(null);

    ProcessingPosition pos = store.load(TENANT, INSTANCE, ProcessingStage.LOAD);

    assertThat(pos).isEqualTo(ProcessingPosition.empty());
    assertThat(pos.completed()).isFalse();
    assertThat(pos.positionMarker()).isNull();
    assertThat(pos.processedCount()).isZero();
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
  }

  @Test
  @DisplayName("advance:透传到 mapper,使用 stage.code() 作为 DB 列值")
  void shouldDelegateAdvance() {
    store.advance(TENANT, INSTANCE, ProcessingStage.LOAD, "row:500", 500L);

    verify(mapper).advance(eq(TENANT), eq(INSTANCE), eq("LOAD"), eq("row:500"), eq(500L));
  }

  @Test
  @DisplayName("markCompleted:透传到 mapper")
  void shouldDelegateMarkCompleted() {
    store.markCompleted(TENANT, INSTANCE, ProcessingStage.GENERATE);

    verify(mapper).markCompleted(eq(TENANT), eq(INSTANCE), eq("GENERATE"));
  }

  @Test
  @DisplayName("load 是只读路径,不会触发 advance / markCompleted")
  void loadShouldNotMutate() {
    when(mapper.findByInstanceAndStage(TENANT, INSTANCE, "LOAD")).thenReturn(null);

    store.load(TENANT, INSTANCE, ProcessingStage.LOAD);

    // findByInstanceAndStage 触发(by when()),advance / markCompleted 不触发
    verify(mapper).findByInstanceAndStage(TENANT, INSTANCE, "LOAD");
  }
}
