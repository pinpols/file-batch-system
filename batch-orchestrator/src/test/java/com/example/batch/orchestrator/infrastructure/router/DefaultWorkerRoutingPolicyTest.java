package com.example.batch.orchestrator.infrastructure.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerRoutingPolicyTest {

  private DefaultWorkerRoutingPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new DefaultWorkerRoutingPolicy();
  }

  @Test
  void shouldReturnNullForNullCandidates() {
    assertThat(policy.select(null)).isNull();
  }

  @Test
  void shouldReturnNullForEmptyCandidates() {
    assertThat(policy.select(List.of())).isNull();
  }

  @Test
  void shouldSelectAvailableWorkerWithHighestPriority() {
    WorkerRouteModel low = route("w1", 1, true);
    WorkerRouteModel high = route("w2", 10, true);
    WorkerRouteModel mid = route("w3", 5, true);

    WorkerRouteModel selected = policy.select(List.of(low, high, mid));
    assertThat(selected.getWorkerId()).isEqualTo("w2");
  }

  @Test
  void shouldSkipUnavailableWorkers() {
    WorkerRouteModel unavailable = route("w1", 10, false);
    WorkerRouteModel available = route("w2", 3, true);

    WorkerRouteModel selected = policy.select(List.of(unavailable, available));
    assertThat(selected.getWorkerId()).isEqualTo("w2");
  }

  @Test
  void shouldFallBackToFirstCandidateWhenAllUnavailable() {
    WorkerRouteModel first = route("w1", 5, false);
    WorkerRouteModel second = route("w2", 10, false);

    WorkerRouteModel selected = policy.select(List.of(first, second));
    // all unavailable → falls back to first element
    assertThat(selected.getWorkerId()).isEqualTo("w1");
  }

  @Test
  void shouldTreatNullPriorityAsZero() {
    WorkerRouteModel withNullPriority = route("w1", null, true);
    WorkerRouteModel withPriority = route("w2", 1, true);

    WorkerRouteModel selected = policy.select(List.of(withNullPriority, withPriority));
    assertThat(selected.getWorkerId()).isEqualTo("w2");
  }

  @Test
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
