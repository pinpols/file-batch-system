package io.github.pinpols.batch.console.domain.file.application.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Pipeline完整快照范围与租户授权")
@ExtendWith(MockitoExtension.class)
class PipelineProgressQueryServiceTest {
  @Mock
  private TenantIdResolver guard;

  @Mock
  private FilePipelineStepRunMapper mapper;

  @Mock
  private ConsoleFileQueryService files;

  @InjectMocks
  private PipelineProgressQueryService service;

  @Test
  @DisplayName("规范化 ID 以稳定共享订阅")
  void shouldNormalizeIdsForStableSharedSubscription() {
    assertThat(PipelineProgressQueryService.normalize(List.of(8L, 3L, 8L))).containsExactly(3L, 8L);
    assertThatThrownBy(() -> PipelineProgressQueryService.normalize(List.of()))
        .isInstanceOfSatisfying(BizException.class, this::assertInvalidIds);
    assertThatThrownBy(() -> PipelineProgressQueryService.normalize(Arrays.asList(1L, null)))
        .isInstanceOfSatisfying(BizException.class, this::assertInvalidIds);
    assertThatThrownBy(() -> PipelineProgressQueryService.normalize(List.of(0L)))
        .isInstanceOfSatisfying(BizException.class, this::assertInvalidIds);
    assertThatThrownBy(() -> PipelineProgressQueryService.normalize(Collections.nCopies(101, 1L)))
        .isInstanceOfSatisfying(BizException.class, this::assertInvalidIds);
  }

  private void assertInvalidIds(BizException failure) {
    assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
    assertThat(failure.getMessageKey()).isEqualTo(ResultCode.INVALID_ARGUMENT.detailKey());
    assertThat(failure.getMessageArgs()).containsExactly("pipelineInstanceIds");
  }

  @Test
  @DisplayName("所有 Pipeline 均按解析后的租户隔离")
  void shouldScopeEveryPipelineUnderResolvedTenant() {
    when(guard.resolveTenant("requested")).thenReturn("owned");
    var pipeline = new PipelineProgressResponse.Pipeline(
        3L, null, null, "RUNNING", "LOAD", "IMPORT", 1, true, List.of("LOAD"), List.of());
    when(files.pipelineSnapshotAuthorized("owned", 3L)).thenReturn(pipeline);
    var result = service.query("requested", List.of(3L, 3L));
    assertThat(result.tenantId()).isEqualTo("owned");
    assertThat(result.pipelines()).containsExactly(pipeline);
    verify(mapper).setTenantContext("owned");
  }

  @Test
  @DisplayName("任一对象读取失败时拒绝返回不完整快照")
  void shouldFailWholeSnapshotWhenAnyObjectCannotBeRead() {
    when(files.pipelineSnapshotAuthorized("owned", 7L))
        .thenThrow(new IllegalStateException("missing owned pipeline"));
    assertThatThrownBy(() -> service.queryAuthorized("owned", List.of(7L)))
        .isInstanceOf(IllegalStateException.class);
  }
}
