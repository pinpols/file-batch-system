package com.example.batch.worker.spi.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.model.WorkerRouteModel;
import org.junit.jupiter.api.Test;

class SpiWorkerRouteAdapterTest {

  @Test
  void buildDefaultRoute_declaresTaskWorkerTypeAndAvailable() {
    WorkerRouteModel route = new SpiWorkerRouteAdapter().buildDefaultRoute();
    assertThat(route.getWorkerType()).isEqualTo("TASK");
    assertThat(route.getAvailable()).isTrue();
  }
}
