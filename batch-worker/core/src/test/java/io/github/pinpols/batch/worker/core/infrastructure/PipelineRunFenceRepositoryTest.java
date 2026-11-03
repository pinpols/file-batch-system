package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PipelineRunStatus;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineFinishParam;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Pipeline 运行代际围栏:写入携带本次代际与目标状态,迟到写入返回 false 不抛错")
class PipelineRunFenceRepositoryTest {

  private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-11-04T00:00:00Z");
  private static final PipelineRunFence RUN = new PipelineRunFence(7L, STARTED_AT);

  private final PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
  private final PlatformPipelineRunRepository repository =
      new PlatformPipelineRunRepository(mapper);

  @Test
  @DisplayName("当前代际的阶段、补偿与终态写入命中一行时返回 true,并带上运行代际和目标状态")
  void shouldApplyWrites_whenRunGenerationStillOwnsRow() {
    when(mapper.updatePipelineStage(7L, STARTED_AT, "LOAD", "RECEIVE")).thenReturn(1);
    when(mapper.markPipelineCompensating(7L, STARTED_AT, "COMPENSATING")).thenReturn(1);
    when(mapper.markPipelineSuccess(any())).thenReturn(1);
    when(mapper.markPipelineFailed(any())).thenReturn(1);

    assertThat(repository.updatePipelineStage(RUN, "LOAD", "RECEIVE")).isTrue();
    assertThat(repository.markPipelineCompensating(RUN)).isTrue();
    assertThat(repository.markPipelineSuccess(RUN, "COMMIT", "COMMIT")).isTrue();
    assertThat(repository.markPipelineFailed(RUN, "LOAD", "RECEIVE")).isTrue();

    ArgumentCaptor<PlatformPipelineFinishParam> success =
        ArgumentCaptor.forClass(PlatformPipelineFinishParam.class);
    verify(mapper).markPipelineSuccess(success.capture());
    assertThat(success.getValue())
        .isEqualTo(new PlatformPipelineFinishParam(
            7L, STARTED_AT, "COMMIT", "COMMIT", PipelineRunStatus.SUCCESS.name()));
    ArgumentCaptor<PlatformPipelineFinishParam> failed =
        ArgumentCaptor.forClass(PlatformPipelineFinishParam.class);
    verify(mapper).markPipelineFailed(failed.capture());
    assertThat(failed.getValue())
        .isEqualTo(new PlatformPipelineFinishParam(
            7L, STARTED_AT, "LOAD", "RECEIVE", PipelineRunStatus.FAILED.name()));
  }

  @Test
  @DisplayName("已被终态或新代际接管时写入未命中,返回 false 且不抛异常")
  void shouldReportStaleWrites_whenNoRowMatches() {
    assertThat(repository.updatePipelineStage(RUN, "LOAD", "RECEIVE")).isFalse();
    assertThat(repository.markPipelineCompensating(RUN)).isFalse();
    assertThat(repository.markPipelineSuccess(RUN, "COMMIT", "COMMIT")).isFalse();
    assertThat(repository.markPipelineFailed(RUN, "LOAD", "RECEIVE")).isFalse();
  }

  @Test
  @DisplayName("未建立 Pipeline 实例时各写入直接跳过,不访问数据库")
  void shouldSkipWrites_whenRunIsAbsent() {
    assertThat(repository.updatePipelineStage(null, "LOAD", "RECEIVE")).isFalse();
    assertThat(repository.markPipelineCompensating(null)).isFalse();
    assertThat(repository.markPipelineSuccess(null, "COMMIT", "COMMIT")).isFalse();
    assertThat(repository.markPipelineFailed(null, "LOAD", "RECEIVE")).isFalse();
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("从运行属性恢复围栏:缺少实例 ID 时为空,代际类型不符时保留实例但代际为空")
  void shouldRestoreFenceFromAttributes() {
    Map<String, Object> attributes = new HashMap<>();
    assertThat(PipelineRunFence.fromAttributes(attributes)).isNull();

    attributes.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 7L);
    attributes.put(PipelineRuntimeKeys.PIPELINE_RUN_STARTED_AT, STARTED_AT);
    assertThat(PipelineRunFence.fromAttributes(attributes)).isEqualTo(RUN);

    attributes.put(PipelineRuntimeKeys.PIPELINE_RUN_STARTED_AT, "2026-11-04T00:00:00Z");
    assertThat(PipelineRunFence.fromAttributes(attributes))
        .isEqualTo(new PipelineRunFence(7L, null));
  }
}
