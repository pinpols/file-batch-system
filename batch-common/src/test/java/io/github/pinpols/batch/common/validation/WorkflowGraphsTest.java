package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作流图共享算法：可达集合与 DSL 节点引用解析")
class WorkflowGraphsTest {

  @Test
  @DisplayName("可达性：沿边传递到达，包含起点，孤立节点不可达")
  void reachableFrom_followsEdgesTransitively() {
    Map<String, List<String>> adjacency = Map.of("a", List.of("b"), "b", List.of("c"));

    assertThat(WorkflowGraphs.reachableFrom(List.of("a"), adjacency))
        .isEqualTo(Set.of("a", "b", "c"));
  }

  @Test
  @DisplayName("可达性：遇到环不会死循环")
  void reachableFrom_terminatesOnCycle() {
    Map<String, List<String>> adjacency = Map.of("a", List.of("b"), "b", List.of("a"));

    assertThat(WorkflowGraphs.reachableFrom(List.of("a"), adjacency)).isEqualTo(Set.of("a", "b"));
  }

  @Test
  @DisplayName("可达性：没有起点时返回空集")
  void reachableFrom_emptyRoots() {
    assertThat(WorkflowGraphs.reachableFrom(List.of(), Map.of("a", List.of("b"))))
        .isEmpty();
  }

  @Test
  @DisplayName("DSL 引用：保留出现顺序和重复项")
  void dslNodeReferences_keepsOrderAndDuplicates() {
    String params = "{\"x\":\"$.nodes.n1.out\",\"y\":\"$.nodes.n2.v $.nodes.n1.z\"}";

    assertThat(WorkflowGraphs.dslNodeReferences(params)).containsExactly("n1", "n2", "n1");
  }

  @Test
  @DisplayName("DSL 引用：参数为 null 或空白时返回空列表")
  void dslNodeReferences_blankParams() {
    assertThat(WorkflowGraphs.dslNodeReferences(null)).isEmpty();
    assertThat(WorkflowGraphs.dslNodeReferences("  ")).isEmpty();
  }
}
