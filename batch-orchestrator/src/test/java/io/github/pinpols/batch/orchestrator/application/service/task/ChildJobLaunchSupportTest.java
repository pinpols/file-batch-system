package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 跨 workflow 嵌套环检测({@link ChildJobLaunchSupport#dispatchJobNode})单测。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("子作业拉起支持: 自引用与跨流程环检测口径")
class ChildJobLaunchSupportTest {

  private static final String TENANT = "t1";
  private static final String CYCLE_KEY = "error.workflow.nested_cycle_detected";

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  private TriggerRequestMapper triggerRequestMapper;

  @Mock
  private WorkflowNodeMapper workflowNodeMapper;

  @Mock
  private WorkflowRunMapper workflowRunMapper;

  @Mock
  private WorkflowNodeRunMapper workflowNodeRunMapper;

  @Mock
  private TaskCreationService taskCreationService;

  @Mock
  private ObjectProvider<LaunchService> launchServiceProvider;

  @Mock
  private WorkflowNodePayloadBuilder payloadBuilder;

  private ChildJobLaunchSupport newSupport() {
    OrchestratorJobMappers jobMappers = new OrchestratorJobMappers(
        jobInstanceMapper,
        jobPartitionMapper,
        jobTaskMapper,
        jobStepInstanceMapper,
        triggerRequestMapper);
    return new ChildJobLaunchSupport(
        jobMappers,
        new OrchestratorWorkflowMappers(
            workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper),
        taskCreationService,
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
    assertThatThrownBy(() ->
            support.dispatchJobNode(parent, run(), resolution(), jobNode("WF_A"), "{}", "trace-1"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> {
          BizException biz = (BizException) ex;
          assertThat(biz.getMessageKey()).isEqualTo(CYCLE_KEY);
          assertThat(biz.getMessageArgs()).contains("WF_A");
        });

    // 环在任何副作用之前被拦：不写虚拟分区、不发起子作业 launch
    verifyNoInteractions(jobPartitionMapper, launchServiceProvider, taskCreationService);
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
    assertThatThrownBy(() ->
            support.dispatchJobNode(wfB, run(), resolution(), jobNode("WF_A"), "{}", "trace-2"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> {
          BizException biz = (BizException) ex;
          assertThat(biz.getMessageKey()).isEqualTo(CYCLE_KEY);
          assertThat(biz.getMessageArgs()).contains("WF_A");
        });

    verifyNoInteractions(jobPartitionMapper, launchServiceProvider, taskCreationService);
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

  @Test
  @DisplayName("无根 Workflow 直接创建节点 Job，不创建虚拟父分区或父任务")
  void shouldLaunchJobNodeWithoutSyntheticParentInstance() {
    ChildJobLaunchSupport support = newSupport();
    WorkflowRunEntity workflowRun = run();
    workflowRun.setRelatedJobInstanceId(null);
    workflowRun.setBizDate(LocalDate.of(2026, 10, 14));
    workflowRun.setDryRun(true);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    org.mockito.Mockito.doAnswer(invocation -> {
          io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity nodeRun =
              invocation.getArgument(0);
          nodeRun.setId(901L);
          return 1;
        })
        .when(workflowNodeRunMapper)
        .insert(any());

    LaunchService launchService = org.mockito.Mockito.mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any(LaunchRequest.class)))
        .thenReturn(new LaunchResponse("child-1", "trace-1"));
    JobInstanceEntity child = instance(7L, "JOB_B", null);
    when(jobInstanceMapper.selectByInstanceNo(TENANT, "child-1")).thenReturn(child);
    when(workflowNodeRunMapper.attachJobInstance(TENANT, 901L, 7L)).thenReturn(1);

    int dispatched = support.dispatchJobNode(
        null, workflowRun, resolution(), jobNode("JOB_B"), "{\"source\":\"test\"}", "trace-1");

    assertThat(dispatched).isEqualTo(1);
    ArgumentCaptor<TriggerRequestEntity> triggerCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insert(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getParentWorkflowRunId()).isEqualTo(900L);
    assertThat(triggerCaptor.getValue().getParentJobInstanceId()).isNull();
    assertThat(triggerCaptor.getValue().getParentVirtualTaskId()).isNull();
    assertThat(triggerCaptor.getValue().getDryRun()).isTrue();
    ArgumentCaptor<LaunchRequest> launchCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(launchCaptor.capture());
    Map<String, Object> params = launchCaptor.getValue().params();
    assertThat(params)
        .containsEntry("_parentWorkflowRunId", 900L)
        .containsEntry("_parentNodeCode", "N1")
        .doesNotContainKey("_parentVirtualTaskId")
        .doesNotContainKey("parentInstanceId");
    assertThat(launchCaptor.getValue().dryRun()).isTrue();
    verify(workflowNodeRunMapper).attachJobInstance(TENANT, 901L, 7L);
    verifyNoInteractions(jobPartitionMapper, taskCreationService);
  }

  @Test
  @DisplayName("批量日闸门等待时保留 READY 节点,不要求同步创建 Job Instance")
  void shouldKeepNodeReadyWhenBatchDayGateDefersChildLaunch() {
    ChildJobLaunchSupport support = newSupport();
    WorkflowRunEntity workflowRun = run();
    workflowRun.setRelatedJobInstanceId(null);
    workflowRun.setBizDate(LocalDate.of(2026, 10, 14));
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    org.mockito.Mockito.doAnswer(invocation -> {
          io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity nodeRun =
              invocation.getArgument(0);
          nodeRun.setId(901L);
          return 1;
        })
        .when(workflowNodeRunMapper)
        .insert(any());
    LaunchService launchService = org.mockito.Mockito.mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any(LaunchRequest.class)))
        .thenReturn(LaunchResponse.skipped("trace-wait"));
    TriggerRequestEntity waiting = new TriggerRequestEntity();
    waiting.setRequestStatus(
        io.github.pinpols.batch.common.enums.TriggerRequestStatus.WAITING.code());
    when(triggerRequestMapper.selectByTenantAndRequestId(anyString(), anyString()))
        .thenReturn(waiting);

    int dispatched = support.dispatchJobNode(
        null, workflowRun, resolution(), jobNode("JOB_B"), "{}", "trace-wait");

    assertThat(dispatched).isZero();
    verify(workflowNodeRunMapper, never()).attachJobInstance(anyString(), anyLong(), anyLong());
    verifyNoInteractions(jobPartitionMapper, taskCreationService);
  }
}
