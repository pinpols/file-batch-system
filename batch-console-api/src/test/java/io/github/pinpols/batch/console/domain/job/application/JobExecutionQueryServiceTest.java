package io.github.pinpols.batch.console.domain.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.file.FilePipelineProgressSnapshotService;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse.Stage;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionMapper;
import io.github.pinpols.batch.console.domain.job.view.JobExecutionView;
import io.github.pinpols.batch.console.shared.progress.PipelineStepProgressSnapshot;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("作业冻结阶段与技术任务分母")
@ExtendWith(MockitoExtension.class)
class JobExecutionQueryServiceTest {
  @Mock
  private JobExecutionMapper mapper;

  @Mock
  private TenantIdResolver guard;

  @Mock
  private FilePipelineProgressSnapshotService filePipelineProgress;

  @InjectMocks
  private JobExecutionQueryService service;

  private JobExecutionView job(String status, Integer version) {
    var job = new JobExecutionView();
    job.setId(1L);
    job.setTenantId("owned");
    job.setJobCode("job");
    job.setInstanceStatus(status);
    job.setSystemPipelineCode("IMPORT");
    job.setSystemPipelineVersion(version);
    job.setPipelineInstanceId(2L);
    job.setExpectedPartitions(4);
    when(mapper.selectJob("owned", 1L)).thenReturn(job);
    when(mapper.selectCounts("owned", 1L)).thenReturn(List.of());
    when(mapper.selectStages("owned", 2L))
        .thenReturn(List.of(new Stage("LOAD", "RUNNING", 9L, 2, 42L, null, null)));
    return job;
  }

  @Test
  @DisplayName("仅运行一个阶段时仍保留全部冻结阶段")
  void shouldKeepAllFrozenStages_whenOnlyOneStageHasRun() {
    job("RUNNING", 1);
    var result = service.queryAuthorized("owned", 1L);
    assertThat(result.stageGraphAvailable()).isTrue();
    assertThat(result.stages())
        .extracting(Stage::stageCode)
        .containsExactly("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK");
    assertThat(result.stages().get(0).stageStatus()).isEqualTo("PENDING");
    assertThat(result.stages().get(4).rowsProcessed()).isEqualTo(42L);
    verify(mapper).setTenantContext("owned");
  }

  @Test
  @DisplayName("作业终止时不伪造成功阶段")
  void shouldNotFabricateSuccessfulStages_whenJobTerminates() {
    job("TERMINATED", 1);
    var result = service.queryAuthorized("owned", 1L);
    assertThat(result.stages().get(0).stageStatus()).isEqualTo("NOT_EXECUTED");
    assertThat(result.stages().get(4).stageStatus()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("版本未知时保留观测阶段且不推测流程图")
  void shouldKeepObservedStagesWithoutInventingGraph_whenVersionIsUnknown() {
    job("RUNNING", 99);
    var result = service.queryAuthorized("owned", 1L);
    assertThat(result.stageGraphAvailable()).isFalse();
    assertThat(result.stages()).hasSize(1);
  }

  @Test
  @DisplayName("目录无法解释时保留意外观测到的阶段")
  void shouldRetainUnexpectedObservedStage_whenCatalogCannotExplainIt() {
    job("RUNNING", 1);
    when(mapper.selectStages("owned", 2L))
        .thenReturn(List.of(new Stage("FUTURE_STAGE", "RUNNING", 10L, 1, null, null, null)));
    var result = service.queryAuthorized("owned", 1L);
    assertThat(result.stageGraphAvailable()).isFalse();
    assertThat(result.stages()).extracting(Stage::stageCode).containsExactly("FUTURE_STAGE");
  }

  @Test
  @DisplayName("授权租户中不存在作业时按失败关闭处理")
  void shouldFailClosed_whenJobDoesNotExistInAuthorizedTenant() {
    assertThatThrownBy(() -> service.queryAuthorized("other", 1L))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND);
          assertThat(failure.getMessageKey()).isEqualTo("error.job.instance_not_found");
        });
    verify(mapper, never()).selectCounts(anyString(), anyLong());
  }

  @Test
  @DisplayName("仅为完全匹配的运行尝试补充实时行数")
  void shouldFillLiveRowsOnlyForExactRunningAttempt() {
    job("RUNNING", 1);
    when(mapper.selectStages("owned", 2L))
        .thenReturn(List.of(new Stage("LOAD", "RUNNING", 9L, 2, null, null, null)));
    var sample = new PipelineStepProgressSnapshot(9L, 2, "RUNNING", 88L, 1000L);
    when(filePipelineProgress.stepsAuthorized("owned", 2L)).thenReturn(List.of(sample));
    var result = service.queryAuthorized("owned", 1L);
    assertThat(result.stages().get(4).rowsProcessed()).isEqualTo(88L);
    assertThat(result.stages().get(4).lastHeartbeatAt()).isEqualTo(Instant.ofEpochMilli(1000L));
  }

  @Test
  @DisplayName("忽略其他运行尝试的实时行数")
  void shouldIgnoreLiveRowsFromOtherAttempt() {
    job("RUNNING", 1);
    when(mapper.selectStages("owned", 2L))
        .thenReturn(List.of(new Stage("LOAD", "RUNNING", 9L, 2, null, null, null)));
    var sample = new PipelineStepProgressSnapshot(8L, 1, "RUNNING", 88L, 1000L);
    when(filePipelineProgress.stepsAuthorized("owned", 2L)).thenReturn(List.of(sample));
    assertThat(service.queryAuthorized("owned", 1L).stages().get(4).rowsProcessed())
        .isNull();
  }

  @Test
  @DisplayName("作业终止后不再合并实时行数")
  void shouldNotAbsorbLiveRowsAfterJobTerminates() {
    job("TERMINATED", 1);
    when(mapper.selectStages("owned", 2L))
        .thenReturn(List.of(new Stage("LOAD", "RUNNING", 9L, 2, null, null, null)));
    assertThat(service.queryAuthorized("owned", 1L).stages().get(4).rowsProcessed())
        .isNull();
    verify(filePipelineProgress, never()).stepsAuthorized("owned", 2L);
  }
}
