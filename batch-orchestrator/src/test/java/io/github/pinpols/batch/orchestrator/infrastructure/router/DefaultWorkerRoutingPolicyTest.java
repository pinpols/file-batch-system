package io.github.pinpols.batch.orchestrator.infrastructure.router;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("默认工作节点路由策略: 空候选处理, 可用性过滤与优先级选择, 全部不可用时回退")
class DefaultWorkerRoutingPolicyTest {

  private DefaultWorkerRoutingPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new DefaultWorkerRoutingPolicy();
  }

  @Test
  @DisplayName("候选列表为空引用时返回空结果, 不抛出异常")
  void shouldReturnNullForNullCandidates() {
    assertThat(policy.select(null)).isNull();
  }

  @Test
  @DisplayName("候选列表为空集合时返回空结果, 不做任何选择")
  void shouldReturnNullForEmptyCandidates() {
    assertThat(policy.select(List.of())).isNull();
  }

  @Test
  @DisplayName("候选列表混有空元素时跳过空元素, 全部为空元素时返回空结果")
  void shouldIgnoreNullCandidates() {
    WorkerRouteModel available = route("w1", 3, true);

    assertThat(policy.select(Arrays.asList(null, available))).isSameAs(available);
    assertThat(policy.select(Arrays.asList(null, null))).isNull();
  }

  @Test
  @DisplayName("多个可用候选时选中优先级数值最高的候选")
  void shouldSelectAvailableWorkerWithHighestPriority() {
    WorkerRouteModel low = route("w1", 1, true);
    WorkerRouteModel high = route("w2", 10, true);
    WorkerRouteModel mid = route("w3", 5, true);

    WorkerRouteModel selected = policy.select(List.of(low, high, mid));
    assertThat(selected.getWorkerId()).isEqualTo("w2");
  }

  @Test
  @DisplayName("存在不可用候选时跳过, 选中可用的候选")
  void shouldSkipUnavailableWorkers() {
    WorkerRouteModel unavailable = route("w1", 10, false);
    WorkerRouteModel available = route("w2", 3, true);

    WorkerRouteModel selected = policy.select(List.of(unavailable, available));
    assertThat(selected.getWorkerId()).isEqualTo("w2");
  }

  @Test
  @DisplayName("全部候选不可用时回退为候选顺序中的第一个")
  void shouldFallBackToFirstCandidateWhenAllUnavailable() {
    WorkerRouteModel first = route("w1", 5, false);
    WorkerRouteModel second = route("w2", 10, false);

    WorkerRouteModel selected = policy.select(List.of(first, second));
    // all unavailable → falls back to first element
    assertThat(selected.getWorkerId()).isEqualTo("w1");
  }

  @Test
  @DisplayName("候选未设置优先级时按零处理, 选中显式设置优先级的候选")
  void shouldTreatNullPriorityAsZero() {
    WorkerRouteModel withNullPriority = route("w1", null, true);
    WorkerRouteModel withPriority = route("w2", 1, true);

    WorkerRouteModel selected = policy.select(List.of(withNullPriority, withPriority));
    assertThat(selected.getWorkerId()).isEqualTo("w2");
  }

  @Test
  @DisplayName("只有一个可用候选时直接选中该候选")
  void shouldSelectSingleAvailableCandidate() {
    WorkerRouteModel only = route("w1", 5, true);
    assertThat(policy.select(List.of(only)).getWorkerId()).isEqualTo("w1");
  }

  // --- helpers ---

  private static WorkerRouteModel route(String workerId, Integer priority, boolean available) {
    WorkerRouteModel model = new WorkerRouteModel();
    model.setWorkerId(workerId);
    model.setPriority(priority);
    model.setAvailable(available);
    return model;
  }
}
