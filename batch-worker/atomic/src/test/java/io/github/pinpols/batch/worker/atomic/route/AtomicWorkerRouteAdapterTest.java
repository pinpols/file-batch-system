package io.github.pinpols.batch.worker.atomic.route;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import org.junit.jupiter.api.Test;

class AtomicWorkerRouteAdapterTest {

  @Test
  void buildDefaultRoute_declaresTaskWorkerTypeAndAvailable() {
    WorkerRouteModel route = new AtomicWorkerRouteAdapter().buildDefaultRoute();
    assertThat(route.getWorkerType()).isEqualTo("ATOMIC");
    assertThat(route.getAvailable()).isTrue();
  }
}
