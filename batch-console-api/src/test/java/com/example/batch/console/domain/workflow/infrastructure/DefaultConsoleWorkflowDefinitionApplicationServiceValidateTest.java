package com.example.batch.console.domain.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.console.domain.job.entity.JobDefinitionEntity;
import com.example.batch.console.domain.job.mapper.JobDefinitionMapper;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.infrastructure.workflow.DefaultConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** WF-design-5 / WF-design-6: JOB 节点 related_job_code + CONDITION 边 condition_expr 校验。 */
class DefaultConsoleWorkflowDefinitionApplicationServiceValidateTest {

  private static final String TENANT = "t1";
  private static final long DEF_ID = 100L;

  private WorkflowDefinitionMapper definitionMapper;
  private WorkflowNodeMapper nodeMapper;
  private WorkflowEdgeMapper edgeMapper;
  private JobDefinitionMapper jobDefinitionMapper;
  private ConsoleTenantGuard tenantGuard;
  private DefaultConsoleWorkflowDefinitionApplicationService service;

  @BeforeEach
  void setUp() {
    definitionMapper = mock(WorkflowDefinitionMapper.class);
    nodeMapper = mock(WorkflowNodeMapper.class);
    edgeMapper = mock(WorkflowEdgeMapper.class);
    jobDefinitionMapper = mock(JobDefinitionMapper.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant(TENANT)).thenReturn(TENANT);

    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);

    service =
        new DefaultConsoleWorkflowDefinitionApplicationService(
            definitionMapper,
            nodeMapper,
            edgeMapper,
            jobDefinitionMapper,
            mock(ConsoleRealtimeDomainEventPublisher.class),
            tenantGuard,
            mock(ConsoleConfigCacheInvalidationService.class));
  }

  @Test
  void shouldFailWhenJobNodeMissingRelatedJobCode() {
    stub(
        List.of(node("n1", "START"), node("n2", "JOB", null), node("n3", "END")),
        List.of(edge("n1", "n2", "ALWAYS"), edge("n2", "n3", "ALWAYS")));

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("JOB node missing related_job_code: n2"));
  }

  @Test
  void shouldFailWhenJobNodeReferencesNonExistentJobDefinition() {
    stub(
        List.of(node("n1", "START"), node("n2", "JOB", "GHOST_JOB"), node("n3", "END")),
        List.of(edge("n1", "n2", "ALWAYS"), edge("n2", "n3", "ALWAYS")));
    when(jobDefinitionMapper.selectByUniqueKey(TENANT, "GHOST_JOB")).thenReturn(null);

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .anyMatch(e -> e.contains("references non-existent job_definition: GHOST_JOB"));
  }

  @Test
  void shouldFailWhenJobNodeReferencesDisabledJobDefinition() {
    JobDefinitionEntity disabled = new JobDefinitionEntity();
    disabled.setEnabled(false);
    stub(
        List.of(node("n1", "START"), node("n2", "JOB", "DISABLED_JOB"), node("n3", "END")),
        List.of(edge("n1", "n2", "ALWAYS"), edge("n2", "n3", "ALWAYS")));
    when(jobDefinitionMapper.selectByUniqueKey(TENANT, "DISABLED_JOB")).thenReturn(disabled);

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .anyMatch(e -> e.contains("references disabled job_definition: DISABLED_JOB"));
  }

  @Test
  void shouldPassWhenJobNodeReferencesEnabledJobDefinition() {
    JobDefinitionEntity enabled = new JobDefinitionEntity();
    enabled.setEnabled(true);
    stub(
        List.of(node("n1", "START"), node("n2", "JOB", "GOOD_JOB"), node("n3", "END")),
        List.of(edge("n1", "n2", "ALWAYS"), edge("n2", "n3", "ALWAYS")));
    when(jobDefinitionMapper.selectByUniqueKey(TENANT, "GOOD_JOB")).thenReturn(enabled);

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void shouldFailWhenConditionEdgeMissingConditionExpr() {
    stub(
        List.of(node("n1", "START"), node("n2", "TASK"), node("n3", "END")),
        List.of(
            edge("n1", "n2", "ALWAYS"), edge("n2", "n3", "CONDITION", null) // missing expr
            ));

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isFalse();
    assertThat(result.errors())
        .anyMatch(e -> e.contains("CONDITION edge missing condition_expr: n2 -> n3"));
  }

  @Test
  void shouldPassWhenConditionEdgeHasConditionExpr() {
    stub(
        List.of(node("n1", "START"), node("n2", "TASK"), node("n3", "END")),
        List.of(edge("n1", "n2", "ALWAYS"), edge("n2", "n3", "CONDITION", "x > 0")));

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void shouldNotRequireConditionExprForOtherEdgeTypes() {
    stub(
        List.of(node("n1", "START"), node("n2", "TASK"), node("n3", "END")),
        List.of(edge("n1", "n2", "SUCCESS"), edge("n2", "n3", "FAILURE")));

    DagValidationResult result = service.validate(DEF_ID, TENANT);

    assertThat(result.valid()).isTrue();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void stub(List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
    when(nodeMapper.selectByQuery(any())).thenReturn(nodes);
    when(edgeMapper.selectByQuery(any())).thenReturn(edges);
  }

  private static WorkflowNodeEntity node(String code, String type) {
    return node(code, type, null);
  }

  private static WorkflowNodeEntity node(String code, String type, String relatedJobCode) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setNodeCode(code);
    n.setNodeType(type);
    n.setRelatedJobCode(relatedJobCode);
    return n;
  }

  private static WorkflowEdgeEntity edge(String from, String to, String edgeType) {
    return edge(from, to, edgeType, null);
  }

  private static WorkflowEdgeEntity edge(
      String from, String to, String edgeType, String conditionExpr) {
    WorkflowEdgeEntity e = new WorkflowEdgeEntity();
    e.setFromNodeCode(from);
    e.setToNodeCode(to);
    e.setEdgeType(edgeType);
    e.setConditionExpr(conditionExpr);
    return e;
  }
}
