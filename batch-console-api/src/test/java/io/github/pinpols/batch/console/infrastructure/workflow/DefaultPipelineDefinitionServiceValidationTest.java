package io.github.pinpols.batch.console.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PipelineType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.rbac.ConsoleMetaQueryService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.PipelineDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("流水线定义保存的固定阶段契约")
class DefaultPipelineDefinitionServiceValidationTest {

  @Test
  @DisplayName("缺失、重复或重排阶段时拒绝创建")
  void shouldRejectIncompleteDuplicateOrReorderedStages() {
    for (List<String> stages : List.of(
        PipelineType.stages("IMPORT").subList(0, 5),
        List.of("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "LOAD"),
        List.of("PREPROCESS", "RECEIVE", "PARSE", "VALIDATE", "LOAD", "FEEDBACK"))) {
      var mapper = mock(PipelineDefinitionMapper.class);
      var meta = mock(ConsoleMetaQueryService.class);
      var guard = mock(ConsoleTenantGuard.class);
      when(guard.resolveTenant("tenant-a")).thenReturn("tenant-a");
      when(meta.pipelineStages()).thenReturn(Map.of("IMPORT", PipelineType.stages("IMPORT")));
      var service = new DefaultPipelineDefinitionService(
          mapper,
          mock(PipelineStepDefinitionMapper.class),
          guard,
          mock(ConsoleRealtimeEventPort.class),
          meta,
          mock(JobResourceAssignmentGuard.class));

      PipelineDefinitionSaveRequest request = request(stages);
      assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class);
      verifyNoInteractions(mapper);
    }
  }

  @Test
  @DisplayName("阶段顺序正确时拒绝与固定位置不符的 step_order")
  void shouldRejectStepOrderDifferentFromFixedPosition() {
    var mapper = mock(PipelineDefinitionMapper.class);
    var meta = mock(ConsoleMetaQueryService.class);
    var guard = mock(ConsoleTenantGuard.class);
    when(guard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(meta.pipelineStages()).thenReturn(Map.of("IMPORT", PipelineType.stages("IMPORT")));
    var service = new DefaultPipelineDefinitionService(
        mapper,
        mock(PipelineStepDefinitionMapper.class),
        guard,
        mock(ConsoleRealtimeEventPort.class),
        meta,
        mock(JobResourceAssignmentGuard.class));

    PipelineDefinitionSaveRequest request = request(PipelineType.stages("IMPORT"));
    request.getSteps().get(1).setStepOrder(0);

    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class);
    verifyNoInteractions(mapper);
  }

  private static PipelineDefinitionSaveRequest request(List<String> stages) {
    PipelineDefinitionSaveRequest request = new PipelineDefinitionSaveRequest();
    request.setTenantId("tenant-a");
    request.setJobCode("job-a");
    request.setPipelineName("Job pipeline");
    request.setPipelineType("IMPORT");
    List<PipelineDefinitionSaveRequest.StepItem> steps = new ArrayList<>();
    for (int index = 0; index < stages.size(); index++) {
      PipelineDefinitionSaveRequest.StepItem step = new PipelineDefinitionSaveRequest.StepItem();
      step.setStepCode("step-" + index);
      step.setStepName("Step " + index);
      step.setStageCode(stages.get(index));
      step.setStepOrder(index);
      step.setImplCode("impl-" + index);
      steps.add(step);
    }
    request.setSteps(steps);
    return request;
  }
}
