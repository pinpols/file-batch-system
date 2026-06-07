package com.example.batch.console.domain.workflow.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest.EdgeItem;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest.NodeItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 纯单测 WorkflowDagValidator(no Spring),每条规则一个 case + 成功路径 + 边界。
 *
 * <p>Pipeline 存在性校验:mock PipelineDefinitionMapper.countByJobCode,只在涉及 FILE_STEP 的 case 调用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowDagValidator")
class WorkflowDagValidatorTest {

  private static final String TENANT = "ta";

  @Mock private PipelineDefinitionMapper pipelineDefinitionMapper;
  @InjectMocks private WorkflowDagValidator validator;

  @Test
  @DisplayName("成功:START → JOB → END 三节点链路 + JOB 引用合法")
  void shouldPass_whenMinimalValidDag() {
    // 准备
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(
        Arrays.asList(node("start", "START"), jobNode("j1", "job_code_1"), node("end", "END")));
    req.setEdges(Arrays.asList(edge("start", "j1"), edge("j1", "end")));

    // 执行并断言
    assertThatCode(() -> validator.validate(TENANT, req)).doesNotThrowAnyException();
    verify(pipelineDefinitionMapper, never()).countByJobCode(anyString(), anyString());
  }

  @Test
  @DisplayName("节点数超 200 → VALIDATION_ERROR too_many_nodes")
  void shouldFail_whenTooManyNodes() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    List<NodeItem> nodes = new ArrayList<>();
    nodes.add(node("start", "START"));
    nodes.add(node("end", "END"));
    for (int i = 0; i < 200; i++) {
      nodes.add(node("n" + i, "TASK"));
    }
    req.setNodes(nodes);
    req.setEdges(List.of(edge("start", "end")));

    assertBizError(req, "error.workflow.dag.too_many_nodes");
  }

  @Test
  @DisplayName("nodeCode 重复 → duplicate_node_code")
  void shouldFail_whenDuplicateNodeCode() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("start", "START"), node("dup", "TASK"), node("dup", "END")));
    req.setEdges(Arrays.asList(edge("start", "dup")));

    assertBizError(req, "error.workflow.dag.duplicate_node_code");
  }

  @Test
  @DisplayName("缺 START → start_count_invalid")
  void shouldFail_whenMissingStart() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("a", "TASK"), node("end", "END")));
    req.setEdges(List.of(edge("a", "end")));

    assertBizError(req, "error.workflow.dag.start_count_invalid");
  }

  @Test
  @DisplayName("多个 START → start_count_invalid")
  void shouldFail_whenMultipleStarts() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("s1", "START"), node("s2", "START"), node("end", "END")));
    req.setEdges(Arrays.asList(edge("s1", "end"), edge("s2", "end")));

    assertBizError(req, "error.workflow.dag.start_count_invalid");
  }

  @Test
  @DisplayName("缺 END → missing_end")
  void shouldFail_whenMissingEnd() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("start", "START"), node("a", "TASK")));
    req.setEdges(List.of(edge("start", "a")));

    assertBizError(req, "error.workflow.dag.missing_end");
  }

  @Test
  @DisplayName("边引用未知 from 节点 → edge_from_unknown")
  void shouldFail_whenEdgeFromUnknown() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("start", "START"), node("end", "END")));
    req.setEdges(List.of(edge("ghost", "end")));

    assertBizError(req, "error.workflow.dag.edge_from_unknown");
  }

  @Test
  @DisplayName("边引用未知 to 节点 → edge_to_unknown")
  void shouldFail_whenEdgeToUnknown() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("start", "START"), node("end", "END")));
    req.setEdges(List.of(edge("start", "ghost")));

    assertBizError(req, "error.workflow.dag.edge_to_unknown");
  }

  @Test
  @DisplayName("DAG 含环 → cycle_detected")
  void shouldFail_whenCycleDetected() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(
        Arrays.asList(
            node("start", "START"), node("a", "TASK"), node("b", "TASK"), node("end", "END")));
    req.setEdges(
        Arrays.asList(edge("start", "a"), edge("a", "b"), edge("b", "a"), edge("a", "end")));

    assertBizError(req, "error.workflow.dag.cycle_detected");
  }

  @Test
  @DisplayName("孤立节点(未从 START 可达)→ node_unreachable")
  void shouldFail_whenNodeUnreachable() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    req.setNodes(Arrays.asList(node("start", "START"), node("end", "END"), node("orphan", "TASK")));
    req.setEdges(List.of(edge("start", "end")));

    assertBizError(req, "error.workflow.dag.node_unreachable");
  }

  @Test
  @DisplayName("JOB 节点 related_job_code 空 → job_related_code_missing")
  void shouldFail_whenJobRelatedCodeMissing() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem job = node("j1", "JOB");
    // 不设置 relatedJobCode
    req.setNodes(Arrays.asList(node("start", "START"), job, node("end", "END")));
    req.setEdges(Arrays.asList(edge("start", "j1"), edge("j1", "end")));

    assertBizError(req, "error.workflow.dag.job_related_code_missing");
  }

  @Test
  @DisplayName("FILE_STEP related_pipeline_code 空 → file_step_related_pipeline_missing")
  void shouldFail_whenFileStepPipelineCodeMissing() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem fs = node("fs", "FILE_STEP");
    req.setNodes(Arrays.asList(node("start", "START"), fs, node("end", "END")));
    req.setEdges(Arrays.asList(edge("start", "fs"), edge("fs", "end")));

    assertBizError(req, "error.workflow.dag.file_step_related_pipeline_missing");
  }

  @Test
  @DisplayName("FILE_STEP pipelineCode 在 pipeline_definition 不存在 → file_step_pipeline_not_found")
  void shouldFail_whenFileStepPipelineNotFound() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem fs = node("fs", "FILE_STEP");
    fs.setRelatedPipelineCode("ghost_pipeline");
    req.setNodes(Arrays.asList(node("start", "START"), fs, node("end", "END")));
    req.setEdges(Arrays.asList(edge("start", "fs"), edge("fs", "end")));
    when(pipelineDefinitionMapper.countByJobCode(eq(TENANT), eq("ghost_pipeline"))).thenReturn(0L);

    // 不能走 assertBizError helper(那个 helper 会 lenient stub 覆盖此处的 0L)
    assertThatThrownBy(() -> validator.validate(TENANT, req))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> {
              assertThat(ex.getCode()).isEqualTo(ResultCode.VALIDATION_ERROR);
              assertThat(ex.getMessageKey())
                  .isEqualTo("error.workflow.dag.file_step_pipeline_not_found");
            });
  }

  @Test
  @DisplayName("FILE_STEP pipelineCode 存在 → 通过")
  void shouldPass_whenFileStepPipelineExists() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem fs = node("fs", "FILE_STEP");
    fs.setRelatedPipelineCode("known_pipeline");
    req.setNodes(Arrays.asList(node("start", "START"), fs, node("end", "END")));
    req.setEdges(Arrays.asList(edge("start", "fs"), edge("fs", "end")));
    when(pipelineDefinitionMapper.countByJobCode(eq(TENANT), eq("known_pipeline"))).thenReturn(1L);

    assertThatCode(() -> validator.validate(TENANT, req)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("GATEWAY 出度 < 2 → gateway_out_degree_too_small")
  void shouldFail_whenGatewayOutDegreeTooSmall() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem gw = node("gw", "GATEWAY");
    gw.setNodeParams("{\"strategy\":\"XOR\"}");
    req.setNodes(Arrays.asList(node("start", "START"), gw, node("end", "END")));
    req.setEdges(Arrays.asList(edge("start", "gw"), edge("gw", "end")));

    assertBizError(req, "error.workflow.dag.gateway_out_degree_too_small");
  }

  @Test
  @DisplayName("GATEWAY nodeParams 空 → gateway_strategy_missing")
  void shouldFail_whenGatewayStrategyMissing() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem gw = node("gw", "GATEWAY");
    // 出度 2,但缺 strategy
    req.setNodes(
        Arrays.asList(
            node("start", "START"), gw, node("a", "TASK"), node("b", "TASK"), node("end", "END")));
    req.setEdges(
        Arrays.asList(
            edge("start", "gw"),
            edge("gw", "a"),
            edge("gw", "b"),
            edge("a", "end"),
            edge("b", "end")));

    assertBizError(req, "error.workflow.dag.gateway_strategy_missing");
  }

  @Test
  @DisplayName("GATEWAY 出度 = 2 且 strategy 非空 → 通过")
  void shouldPass_whenGatewayValid() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    NodeItem gw = node("gw", "GATEWAY");
    gw.setNodeParams("{\"strategy\":\"XOR\"}");
    req.setNodes(
        Arrays.asList(
            node("start", "START"), gw, node("a", "TASK"), node("b", "TASK"), node("end", "END")));
    req.setEdges(
        Arrays.asList(
            edge("start", "gw"),
            edge("gw", "a"),
            edge("gw", "b"),
            edge("a", "end"),
            edge("b", "end")));

    assertThatCode(() -> validator.validate(TENANT, req)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("MAX_NODES 边界:200 节点(含 START/END)+ 链式 → 不抛")
  void shouldPass_whenExactlyMaxNodes() {
    WorkflowDefinitionSaveRequest req = baseRequest();
    int total = WorkflowDagValidator.MAX_NODES;
    List<NodeItem> nodes = new ArrayList<>();
    List<EdgeItem> edges = new ArrayList<>();
    nodes.add(node("start", "START"));
    for (int i = 0; i < total - 2; i++) {
      nodes.add(node("t" + i, "TASK"));
    }
    nodes.add(node("end", "END"));
    // 链式串起
    for (int i = 0; i < nodes.size() - 1; i++) {
      edges.add(edge(nodes.get(i).getNodeCode(), nodes.get(i + 1).getNodeCode()));
    }
    req.setNodes(nodes);
    req.setEdges(edges);

    assertThatCode(() -> validator.validate(TENANT, req)).doesNotThrowAnyException();
    assertThat(nodes).hasSize(total);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void assertBizError(WorkflowDefinitionSaveRequest req, String expectedKey) {
    // 部分 case 下 mapper 可能被 stub 但不触发,保留 lenient 防 strict 噪音(仅在含 FILE_STEP 的 path 才会被调用)
    lenient()
        .when(pipelineDefinitionMapper.countByJobCode(anyString(), anyString()))
        .thenReturn(1L);
    assertThatThrownBy(() -> validator.validate(TENANT, req))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> {
              assertThat(ex.getCode()).isEqualTo(ResultCode.VALIDATION_ERROR);
              assertThat(ex.getMessageKey()).isEqualTo(expectedKey);
            });
  }

  private static WorkflowDefinitionSaveRequest baseRequest() {
    WorkflowDefinitionSaveRequest req = new WorkflowDefinitionSaveRequest();
    req.setTenantId(TENANT);
    req.setWorkflowCode("wf_ok");
    req.setWorkflowName("wf");
    req.setWorkflowType("DAG");
    return req;
  }

  private static NodeItem node(String code, String type) {
    NodeItem n = new NodeItem();
    n.setNodeCode(code);
    n.setNodeType(type);
    return n;
  }

  private static NodeItem jobNode(String code, String jobCode) {
    NodeItem n = node(code, "JOB");
    n.setRelatedJobCode(jobCode);
    return n;
  }

  private static EdgeItem edge(String from, String to) {
    EdgeItem e = new EdgeItem();
    e.setFromNodeCode(from);
    e.setToNodeCode(to);
    return e;
  }
}
