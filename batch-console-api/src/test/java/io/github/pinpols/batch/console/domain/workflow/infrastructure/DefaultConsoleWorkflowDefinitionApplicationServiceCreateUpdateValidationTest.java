package io.github.pinpols.batch.console.domain.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultConsoleWorkflowDefinitionApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #5:create / update 入口必须强制走 DAG validator(防脚本/旧前端绕过 fullUpdate 的校验)。 */
@DisplayName("workflow create/update 强制 DAG 校验")
class DefaultConsoleWorkflowDefinitionApplicationServiceCreateUpdateValidationTest {

  private static final String TENANT = "t1";
  private static final long DEF_ID = 100L;

  private WorkflowDefinitionMapper definitionMapper;
  private WorkflowNodeMapper nodeMapper;
  private WorkflowEdgeMapper edgeMapper;
  private WorkflowDagValidator dagValidator;
  private DefaultConsoleWorkflowDefinitionApplicationService service;

  @BeforeEach
  void setUp() {
    definitionMapper = mock(WorkflowDefinitionMapper.class);
    nodeMapper = mock(WorkflowNodeMapper.class);
    edgeMapper = mock(WorkflowEdgeMapper.class);
    dagValidator = mock(WorkflowDagValidator.class);
    ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant(TENANT)).thenReturn(TENANT);

    service =
        new DefaultConsoleWorkflowDefinitionApplicationService(
            definitionMapper,
            nodeMapper,
            edgeMapper,
            mock(WorkflowDefinitionVersionMapper.class),
            mock(JobDefinitionMapper.class),
            mock(ConsoleRealtimeDomainEventPublisher.class),
            tenantGuard,
            mock(ConsoleConfigCacheInvalidationService.class),
            mock(WorkflowDesignLockService.class),
            dagValidator,
            new ObjectMapper());
  }

  private WorkflowDefinitionSaveRequest request() {
    WorkflowDefinitionSaveRequest req = new WorkflowDefinitionSaveRequest();
    req.setTenantId(TENANT);
    req.setWorkflowCode("WF_CYCLE");
    req.setWorkflowName("含环工作流");
    req.setWorkflowType("STANDARD");
    return req;
  }

  @Test
  @DisplayName("create:validator 抛错 → 异常传播,绝不 insert 节点/边")
  void create_blocksPersist_whenValidatorThrows() {
    // arrange
    when(definitionMapper.selectByUniqueKey(eq(TENANT), eq("WF_CYCLE"), eq(1))).thenReturn(null);
    doThrow(BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.dag.cycle_detected"))
        .when(dagValidator)
        .validate(eq(TENANT), any());

    // act + assert
    assertThatThrownBy(() -> service.create(request())).isInstanceOf(BizException.class);
    verify(dagValidator).validate(eq(TENANT), any());
    verify(definitionMapper, never()).insert(any());
    verify(nodeMapper, never()).upsertWorkflowNode(any());
  }

  @Test
  @DisplayName("update:validator 抛错 → 异常传播,绝不 update 定义/删建节点")
  void update_blocksPersist_whenValidatorThrows() {
    // arrange
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode("WF_CYCLE");
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    doThrow(BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.dag.cycle_detected"))
        .when(dagValidator)
        .validate(eq(TENANT), any());

    // act + assert
    assertThatThrownBy(() -> service.update(DEF_ID, request())).isInstanceOf(BizException.class);
    verify(dagValidator).validate(eq(TENANT), any());
    verify(definitionMapper, never()).updateWorkflowDefinition(any(), any(), any(), any(), any());
    verify(nodeMapper, never()).deleteByWorkflowDefinitionId(any());
  }
}
