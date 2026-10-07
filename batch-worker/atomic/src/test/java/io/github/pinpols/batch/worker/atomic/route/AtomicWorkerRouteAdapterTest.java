package io.github.pinpols.batch.worker.atomic.route;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("原子 Worker 路由适配器: 默认路由声明")
class AtomicWorkerRouteAdapterTest {

  @Test
  @DisplayName("构建默认路由时, 应声明为原子任务型 Worker 且默认可用")
  void buildDefaultRoute_declaresTaskWorkerTypeAndAvailable() {
    WorkerRouteModel route = new AtomicWorkerRouteAdapter().buildDefaultRoute();
    assertThat(route.getWorkerType()).isEqualTo("ATOMIC");
    assertThat(route.getAvailable()).isTrue();
  }
}
