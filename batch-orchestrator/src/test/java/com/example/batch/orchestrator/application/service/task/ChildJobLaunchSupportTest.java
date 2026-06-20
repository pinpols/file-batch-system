package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.service.LaunchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 跨 workflow 嵌套环检测({@link ChildJobLaunchSupport#dispatchJobNode})单测。 */
@ExtendWith(MockitoExtension.class)
class ChildJobLaunchSupportTest {

  private static final String TENANT = "t1";
  private static final String CYCLE_KEY = "error.workflow.nested_cycle_detected";

  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;
  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private OrchestratorWorkflowMappers workflowMappers;
  @Mock private ObjectProvider<TaskExecutionService> taskExecutionServiceProvider;
  @Mock private ObjectProvider<LaunchService> launchServiceProvider;
  @Mock private WorkflowNodePayloadBuilder payloadBuilder;

  private ChildJobLaunchSupport newSupport() {
    OrchestratorJobMappers jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper);
    return new ChildJobLaunchSupport(
        jobMappers,
        workflowMappers,
        taskExecutionServiceProvider,
        launchServiceProvider,
        payloadBuilder);
  }

  private JobInstanceEntity instance(Long id, String jobCode, Long parentInstanceId) {
    JobInstanceEntity e = new JobInstanceEntity();
    e.setId(id);
    e.setTenantId(TENANT);
    e.setJobCode(jobCode);
    e.setParentInstanceId(parentInstanceId);
    return e;
  }

  private WorkflowNodeEntity jobNode(String relatedJobCode) {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("N1");
    node.setNodeType("JOB");
    node.setRelatedJobCode(relatedJobCode);
    return node;
  }

  private WorkflowRunEntity run() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(900L);
    run.setTenantId(TENANT);
    return run;
  }

  private WorkflowDagService.DagNodeResolution resolution() {
    return new WorkflowDagService.DagNodeResolution("N1", "JOB");
  }

  @Test
  @DisplayName("自引用：JOB 节点指向自身所在 workflow 时直接判定为环")
  void shouldRejectSelfReferenceCycle() {
    // arrange：顶层 workflow WF_A,JOB 节点引用 WF_A 自身
    ChildJobLaunchSupport support = newSupport();
    JobInstanceEntity parent = instance(1L, "WF_A", null);

    // act / assert
    assertThatThrownBy(
            () ->
                support.dispatchJobNode(
                    parent, run(), resolution(), jobNode("WF_A"), "{}", "trace-1"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> {
              BizException biz = (BizException) ex;
              assertThat(biz.getMessageKey()).isEqualTo(CYCLE_KEY);
              assertThat(biz.getMessageArgs()).contains("WF_A");
            });

    // 环在任何副作用之前被拦：不写虚拟分区、不发起子作业 launch
    verifyNoInteractions(jobPartitionMapper, launchServiceProvider, taskExecutionServiceProvider);
    // 顶层父无 parent_instance_id，无需上溯 DB
    verify(jobInstanceMapper, never()).selectById(anyString(), anyLong());
  }

  @Test
  @DisplayName("间接环：A→B 后 B 的 JOB 节点又指回 A,沿 parent 链上溯命中 A")
  void shouldRejectIndirectCycleAcrossWorkflows() {
    // arrange：当前父是 WF_B(id=2),其 parent 是 WF_A(id=1)；JOB 节点引用 WF_A
    ChildJobLaunchSupport support = newSupport();
    JobInstanceEntity wfB = instance(2L, "WF_B", 1L);
    when(jobInstanceMapper.selectById(TENANT, 1L)).thenReturn(instance(1L, "WF_A", null));

    // act / assert
    assertThatThrownBy(
            () ->
                support.dispatchJobNode(wfB, run(), resolution(), jobNode("WF_A"), "{}", "trace-2"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> {
              BizException biz = (BizException) ex;
              assertThat(biz.getMessageKey()).isEqualTo(CYCLE_KEY);
              assertThat(biz.getMessageArgs()).contains("WF_A");
            });

    verifyNoInteractions(jobPartitionMapper, launchServiceProvider, taskExecutionServiceProvider);
  }

  @Test
  @DisplayName("无环：引用与祖先不冲突的 workflow 时环检测放行(不抛 nested_cycle)")
  void shouldNotRejectWhenNoCycle() {
    // arrange：WF_B(parent=WF_A),JOB 节点引用全新的 WF_C
    ChildJobLaunchSupport support = newSupport();
    JobInstanceEntity wfB = instance(2L, "WF_B", 1L);
    when(jobInstanceMapper.selectById(TENANT, 1L)).thenReturn(instance(1L, "WF_A", null));

    // act：环检测放行后会继续走真正的拉起逻辑(本测试不为其铺 mock,可能抛其它异常),
    // 只断言"没有抛 nested_cycle 这个环异常"。
    try {
      support.dispatchJobNode(wfB, run(), resolution(), jobNode("WF_C"), "{}", "trace-3");
    } catch (BizException ex) {
      // assert：若抛 BizException，必须不是嵌套环
      assertThat(ex.getMessageKey()).isNotEqualTo(CYCLE_KEY);
    } catch (RuntimeException ignored) {
      // 后续 launch 路径 mock 不全导致的 NPE 等与本用例无关，环检测已放行即达目的
    }

    // 确认确实沿 parent 链上溯到了 WF_A
    verify(jobInstanceMapper).selectById(TENANT, 1L);
  }
}
