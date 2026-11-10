package io.github.pinpols.batch.console.domain.workflow.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowTopologyQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowTopologyResponse;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeRunQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowRunQuery;
import io.github.pinpols.batch.console.domain.workflow.support.ConsoleWorkflowQueryMappers;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.persistence.TenantDatabaseContext;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("工作流拓扑查询:具名查询构造与运行明细组装")
class ConsoleWorkflowQueryServiceTopologyTest {

  @Test
  @DisplayName("运行列表将编码和定义 ID 同时传给列表及计数查询")
  void shouldPassWorkflowCodeAndDefinitionIdToRunQueries() {
    WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
    ConsoleWorkflowQueryMappers mappers = new ConsoleWorkflowQueryMappers(
        mock(WorkflowDefinitionMapper.class),
        mock(WorkflowNodeMapper.class),
        mock(WorkflowEdgeMapper.class),
        runMapper,
        mock(WorkflowNodeRunMapper.class));
    ConsoleWorkflowQueryService service = new ConsoleWorkflowQueryService(
        requestedTenantId -> requestedTenantId, mappers, mock(LocalizedErrorRenderer.class));
    when(runMapper.selectByQuery(any())).thenReturn(List.of());
    WorkflowRunQueryRequest request = new WorkflowRunQueryRequest();
    request.setTenantId("tenant-a");
    request.setWorkflowCode("WF");
    request.setWorkflowDefinitionId(2L);

    service.workflowRuns(request);

    ArgumentCaptor<WorkflowRunQuery> selected = ArgumentCaptor.forClass(WorkflowRunQuery.class);
    ArgumentCaptor<WorkflowRunQuery> counted = ArgumentCaptor.forClass(WorkflowRunQuery.class);
    verify(runMapper).selectByQuery(selected.capture());
    verify(runMapper).countByQuery(counted.capture());
    assertThat(selected.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(selected.getValue().workflowCode()).isEqualTo("WF");
    assertThat(selected.getValue().workflowDefinitionId()).isEqualTo(2L);
    assertThat(counted.getValue()).isEqualTo(selected.getValue());
  }

  @Test
  @DisplayName("按请求标识查询前仍执行真实租户守卫")
  void shouldRejectOtherTenantBeforeQueryingRequestId() {
    WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
    ConsoleWorkflowQueryMappers mappers = new ConsoleWorkflowQueryMappers(
        mock(WorkflowDefinitionMapper.class),
        mock(WorkflowNodeMapper.class),
        mock(WorkflowEdgeMapper.class),
        runMapper,
        mock(WorkflowNodeRunMapper.class));
    ConsoleRequestMetadataResolver metadata = mock(ConsoleRequestMetadataResolver.class);
    when(metadata.current()).thenThrow(new IllegalStateException("request scope missing"));
    ConsoleTenantGuard guard = new ConsoleTenantGuard(metadata, mock(TenantDatabaseContext.class));
    ConsoleWorkflowQueryService service =
        new ConsoleWorkflowQueryService(guard, mappers, mock(LocalizedErrorRenderer.class));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", "tenant-a", Set.of("ROLE_TENANT_USER")), "ignored"));
    try {
      WorkflowRunQueryRequest request = new WorkflowRunQueryRequest();
      request.setTenantId("tenant-b");
      request.setRequestId("same-request");
      assertThatThrownBy(() -> service.workflowRuns(request))
          .isInstanceOf(BizException.class)
          .extracting(error -> ((BizException) error).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
      verifyNoInteractions(runMapper);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  @DisplayName("作业详情关联查询按租户内 Job 编码筛选工作流")
  void shouldFilterWorkflowDefinitionsByRelatedJobCode() {
    WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
    ConsoleWorkflowQueryMappers mappers = new ConsoleWorkflowQueryMappers(
        definitionMapper,
        mock(WorkflowNodeMapper.class),
        mock(WorkflowEdgeMapper.class),
        mock(WorkflowRunMapper.class),
        mock(WorkflowNodeRunMapper.class));
    ConsoleWorkflowQueryService service = new ConsoleWorkflowQueryService(
        requestedTenantId -> requestedTenantId, mappers, mock(LocalizedErrorRenderer.class));
    when(definitionMapper.selectByQuery(any())).thenReturn(List.of());
    when(definitionMapper.countByQuery(any())).thenReturn(0L);
    WorkflowDefinitionQueryRequest request = new WorkflowDefinitionQueryRequest();
    request.setTenantId("tenant-a");
    request.setRelatedJobCode("IMPORT_ORDERS");
    request.setSingleJobWorkflow(Boolean.TRUE);

    service.workflowDefinitions(request);

    ArgumentCaptor<WorkflowDefinitionQuery> query =
        ArgumentCaptor.forClass(WorkflowDefinitionQuery.class);
    verify(definitionMapper).selectByQuery(query.capture());
    assertThat(query.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(query.getValue().relatedJobCode()).isEqualTo("IMPORT_ORDERS");
    assertThat(query.getValue().singleJobWorkflow()).isTrue();
  }

  @Test
  @DisplayName("指定运行实例时按租户与定义查询节点、边和运行明细")
  void shouldBuildTopologyQueriesFromNamedBusinessFields() {
    WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
    WorkflowNodeMapper nodeMapper = mock(WorkflowNodeMapper.class);
    WorkflowEdgeMapper edgeMapper = mock(WorkflowEdgeMapper.class);
    WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
    WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    ConsoleWorkflowQueryMappers mappers = new ConsoleWorkflowQueryMappers(
        definitionMapper, nodeMapper, edgeMapper, runMapper, nodeRunMapper);
    ConsoleWorkflowQueryService service = new ConsoleWorkflowQueryService(
        requestedTenantId -> requestedTenantId, mappers, mock(LocalizedErrorRenderer.class));

    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(7L);
    definition.setTenantId("t1");
    definition.setWorkflowCode("flow-a");
    definition.setEnabled(true);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(9L);
    run.setTenantId("t1");
    run.setWorkflowDefinitionId(7L);
    when(definitionMapper.selectByQuery(any())).thenReturn(List.of(definition));
    when(nodeMapper.selectByQuery(any())).thenReturn(List.of());
    when(edgeMapper.selectByQuery(any())).thenReturn(List.of());
    when(runMapper.selectByQuery(any())).thenReturn(List.of(run));
    when(nodeRunMapper.selectByQuery(any())).thenReturn(List.of());

    WorkflowTopologyQueryRequest request = new WorkflowTopologyQueryRequest();
    request.setTenantId("t1");
    request.setWorkflowCode("flow-a");
    request.setVersion(3);
    request.setWorkflowRunId(9L);

    ConsoleWorkflowTopologyResponse response = service.workflowTopology(request);

    assertThat(response.workflowDefinition()).isNotNull();
    assertThat(response.workflowRuns()).hasSize(1);
    ArgumentCaptor<WorkflowDefinitionQuery> definitionQuery =
        ArgumentCaptor.forClass(WorkflowDefinitionQuery.class);
    verify(definitionMapper).selectByQuery(definitionQuery.capture());
    assertThat(definitionQuery.getValue().tenantId()).isEqualTo("t1");
    assertThat(definitionQuery.getValue().enabled()).isTrue();
    verify(nodeMapper).selectByQuery(any(WorkflowNodeQuery.class));
    verify(edgeMapper).selectByQuery(any(WorkflowEdgeQuery.class));
    verify(runMapper).selectByQuery(any(WorkflowRunQuery.class));
    verify(nodeRunMapper).selectByQuery(any(WorkflowNodeRunQuery.class));
  }
}
