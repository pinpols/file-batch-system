package io.github.pinpols.batch.console.domain.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
import io.github.pinpols.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultConsoleWorkflowDefinitionApplicationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * workflow-dag-designer Polish — fullUpdate 同事务追加版本快照 + listVersions / getVersion 真实读路径。
 *
 * <p>覆盖 4 case:fullUpdate 后历史表有 1 行 / 多次 fullUpdate 历史表 N 行 / list 返回完整(current flag) / detail
 * 历史版本可读(JSONB 反序列化)。
 */
@DisplayName("workflow definition version history (V167 — Polish 闭环)")
class DefaultConsoleWorkflowDefinitionApplicationServiceVersionTest {

  private static final String TENANT = "t1";
  private static final long DEF_ID = 100L;
  private static final String USER = "alice";
  private static final String WORKFLOW_CODE = "wf_demo";

  private WorkflowDefinitionMapper definitionMapper;
  private WorkflowNodeMapper nodeMapper;
  private WorkflowEdgeMapper edgeMapper;
  private WorkflowDefinitionVersionMapper versionMapper;
  private WorkflowDesignLockService lockService;
  private DefaultConsoleWorkflowDefinitionApplicationService service;

  @BeforeEach
  void setUp() {
    definitionMapper = mock(WorkflowDefinitionMapper.class);
    nodeMapper = mock(WorkflowNodeMapper.class);
    edgeMapper = mock(WorkflowEdgeMapper.class);
    versionMapper = mock(WorkflowDefinitionVersionMapper.class);
    lockService = mock(WorkflowDesignLockService.class);
    ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant(TENANT)).thenReturn(TENANT);

    service =
        new DefaultConsoleWorkflowDefinitionApplicationService(
            definitionMapper,
            nodeMapper,
            edgeMapper,
            versionMapper,
            mock(JobDefinitionMapper.class),
            mock(ConsoleRealtimeDomainEventPublisher.class),
            tenantGuard,
            mock(ConsoleConfigCacheInvalidationService.class),
            lockService,
            mock(WorkflowDagValidator.class),
            new ObjectMapper());
  }

  @Test
  @DisplayName("fullUpdate 成功 → 历史表追加 1 行(version = oldVersion + 1)")
  void shouldAppendSnapshot_whenFullUpdateSucceeds() {
    // 准备
    WorkflowDefinitionEntity def = defEntity(3);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(lockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder(USER, Instant.now().plusSeconds(60)));
    when(definitionMapper.updateAndBumpVersion(eq(TENANT), eq(DEF_ID), eq(3), any(), any(), any()))
        .thenReturn(1);
    when(nodeMapper.selectByQuery(any(WorkflowNodeQuery.class)))
        .thenReturn(List.of(nodeEntity("start", "START"), nodeEntity("end", "END")));
    when(edgeMapper.selectByQuery(any(WorkflowEdgeQuery.class)))
        .thenReturn(List.of(edgeEntity("start", "end")));

    // 执行
    service.fullUpdate(DEF_ID, fullUpdateBody(3), USER);

    // 断言
    ArgumentCaptor<WorkflowDefinitionVersionInsertParam> captor =
        ArgumentCaptor.forClass(WorkflowDefinitionVersionInsertParam.class);
    verify(versionMapper, times(1)).insertVersionSnapshot(captor.capture());
    WorkflowDefinitionVersionInsertParam p = captor.getValue();
    assertThat(p.getTenantId()).isEqualTo(TENANT);
    assertThat(p.getWorkflowDefinitionId()).isEqualTo(DEF_ID);
    assertThat(p.getWorkflowCode()).isEqualTo(WORKFLOW_CODE);
    assertThat(p.getVersion()).isEqualTo(4); // bumped from 3
    assertThat(p.getSavedBy()).isEqualTo(USER);
    assertThat(p.getNodesJson()).contains("\"nodeCode\":\"start\"");
    assertThat(p.getEdgesJson()).contains("\"fromNodeCode\":\"start\"");
    assertThat(p.getSummary()).isNull();
  }

  @Test
  @DisplayName("多次 fullUpdate → 历史表追加 N 行(每次 bump version)")
  void shouldAppendMultipleSnapshots_whenFullUpdatedNTimes() {
    // 准备
    WorkflowDefinitionEntity def = defEntity(1);
    // 模拟主表 version 在两次调用间从 1 → 2 → 3
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def, defEntity(2));
    when(lockService.currentHolder(TENANT, DEF_ID))
        .thenReturn(new LockHolder(USER, Instant.now().plusSeconds(60)));
    when(definitionMapper.updateAndBumpVersion(eq(TENANT), eq(DEF_ID), any(), any(), any(), any()))
        .thenReturn(1);
    when(nodeMapper.selectByQuery(any(WorkflowNodeQuery.class)))
        .thenReturn(Collections.emptyList());
    when(edgeMapper.selectByQuery(any(WorkflowEdgeQuery.class)))
        .thenReturn(Collections.emptyList());

    // 执行
    service.fullUpdate(DEF_ID, fullUpdateBody(1), USER);
    service.fullUpdate(DEF_ID, fullUpdateBody(2), USER);

    // 断言
    ArgumentCaptor<WorkflowDefinitionVersionInsertParam> captor =
        ArgumentCaptor.forClass(WorkflowDefinitionVersionInsertParam.class);
    verify(versionMapper, times(2)).insertVersionSnapshot(captor.capture());
    assertThat(captor.getAllValues()).extracting("version").containsExactly(2, 3);
  }

  @Test
  @DisplayName("listVersions 真实读历史表 → 全量返回 + 最新 version current=true")
  void shouldReturnFullList_whenHistoryHasRows() {
    // 准备 — 主表 current = 5,历史表 3 行 (5/4/3)
    WorkflowDefinitionEntity def = defEntity(5);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(versionMapper.listByDefinitionId(TENANT, DEF_ID))
        .thenReturn(List.of(versionRow(5, "alice"), versionRow(4, "bob"), versionRow(3, "alice")));

    // 执行
    List<WorkflowDefinitionVersionSummaryResponse> versions = service.listVersions(DEF_ID, TENANT);

    // 断言
    assertThat(versions).hasSize(3);
    assertThat(versions.get(0).version()).isEqualTo(5);
    assertThat(versions.get(0).current()).isTrue();
    assertThat(versions.get(0).savedBy()).isEqualTo("alice");
    assertThat(versions.get(1).current()).isFalse();
    assertThat(versions.get(2).current()).isFalse();
  }

  @Test
  @DisplayName("listVersions 历史表无数据 → 单条 current 降级(兼容 PR #370)")
  void shouldDegradeToSingleCurrent_whenHistoryEmpty() {
    WorkflowDefinitionEntity def = defEntity(1);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    when(versionMapper.listByDefinitionId(TENANT, DEF_ID)).thenReturn(Collections.emptyList());

    List<WorkflowDefinitionVersionSummaryResponse> versions = service.listVersions(DEF_ID, TENANT);

    assertThat(versions).hasSize(1);
    assertThat(versions.get(0).version()).isEqualTo(1);
    assertThat(versions.get(0).current()).isTrue();
    verify(versionMapper, never()).findByDefinitionIdAndVersion(any(), any(), any());
  }

  @Test
  @DisplayName("getVersion 历史 version → JSONB 反序列化 → detail 可读")
  void shouldReadHistoricalVersion_whenSnapshotPresent() {
    // 准备 — 主表 current = 5;读 version=3 走快照路径
    WorkflowDefinitionEntity def = defEntity(5);
    when(definitionMapper.selectById(TENANT, DEF_ID)).thenReturn(def);
    WorkflowDefinitionVersionEntity snap = new WorkflowDefinitionVersionEntity();
    snap.setWorkflowDefinitionId(DEF_ID);
    snap.setTenantId(TENANT);
    snap.setWorkflowCode(WORKFLOW_CODE);
    snap.setVersion(3);
    snap.setWorkflowName("v3-name");
    snap.setWorkflowType("DAG");
    snap.setEnabled(true);
    snap.setNodesJson(
        "[{\"nodeCode\":\"start\",\"nodeType\":\"START\"},"
            + "{\"nodeCode\":\"end\",\"nodeType\":\"END\"}]");
    snap.setEdgesJson("[{\"fromNodeCode\":\"start\",\"toNodeCode\":\"end\"}]");
    snap.setSavedAt(Instant.parse("2026-06-01T00:00:00Z"));
    when(versionMapper.findByDefinitionIdAndVersion(TENANT, DEF_ID, 3)).thenReturn(snap);

    // 执行
    WorkflowDefinitionDetailResponse detail = service.getVersion(DEF_ID, TENANT, 3);

    // 断言
    assertThat(detail.version()).isEqualTo(3);
    assertThat(detail.workflowName()).isEqualTo("v3-name");
    assertThat(detail.nodes()).hasSize(2);
    assertThat(detail.nodes()).extracting("nodeCode").containsExactly("start", "end");
    assertThat(detail.edges()).hasSize(1);
    assertThat(detail.edges().get(0).fromNodeCode()).isEqualTo("start");
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static WorkflowDefinitionEntity defEntity(int version) {
    WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
    def.setId(DEF_ID);
    def.setTenantId(TENANT);
    def.setWorkflowCode(WORKFLOW_CODE);
    def.setWorkflowName("demo");
    def.setWorkflowType("DAG");
    def.setVersion(version);
    def.setEnabled(true);
    return def;
  }

  private static WorkflowDefinitionFullUpdateRequest fullUpdateBody(int expectedVersion) {
    WorkflowDefinitionSaveRequest save = new WorkflowDefinitionSaveRequest();
    save.setTenantId(TENANT);
    save.setWorkflowCode(WORKFLOW_CODE);
    save.setWorkflowName("demo");
    save.setWorkflowType("DAG");
    save.setEnabled(true);
    save.setNodes(new ArrayList<>());
    save.setEdges(new ArrayList<>());
    WorkflowDefinitionFullUpdateRequest req = new WorkflowDefinitionFullUpdateRequest();
    req.setDefinition(save);
    req.setExpectedVersion(expectedVersion);
    return req;
  }

  private static WorkflowNodeEntity nodeEntity(String code, String type) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setWorkflowDefinitionId(DEF_ID);
    n.setNodeCode(code);
    n.setNodeType(type);
    n.setEnabled(true);
    return n;
  }

  private static WorkflowEdgeEntity edgeEntity(String from, String to) {
    WorkflowEdgeEntity e = new WorkflowEdgeEntity();
    e.setWorkflowDefinitionId(DEF_ID);
    e.setFromNodeCode(from);
    e.setToNodeCode(to);
    e.setEdgeType("ALWAYS");
    e.setEnabled(true);
    return e;
  }

  private static WorkflowDefinitionVersionEntity versionRow(int version, String savedBy) {
    WorkflowDefinitionVersionEntity v = new WorkflowDefinitionVersionEntity();
    v.setVersion(version);
    v.setSavedBy(savedBy);
    v.setSavedAt(Instant.now());
    return v;
  }
}
