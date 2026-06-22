package com.example.batch.worker.atomic.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.model.WorkerRouteModel;
import org.junit.jupiter.api.Test;

class AtomicWorkerRouteAdapterTest {

  @Test
  void buildDefaultRoute_declaresTaskWorkerTypeAndAvailable() {
    WorkerRouteModel route = new AtomicWorkerRouteAdapter().buildDefaultRoute();
    assertThat(route.getWorkerType()).isEqualTo("ATOMIC");
    assertThat(route.getAvailable()).isTrue();
  }
}
