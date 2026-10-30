package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

@DisplayName("维护 HTTP 指标：Prometheus 标签结构与真实错误保留")
class MaintenanceHttpObservationConventionTest {
  @Test
  @DisplayName("计划 503、真实 500 与正常请求可在同一指标族共存且分类准确")
  void shouldRecordConsistentTagKeysAcrossPlannedAndOrdinaryRequests() throws Exception {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      ObservationRegistry observations = ObservationRegistry.create();
      DefaultMeterObservationHandler handler = new DefaultMeterObservationHandler(registry);
      observations.observationConfig().observationHandler(handler);
      MaintenanceHttpObservationConvention convention = new MaintenanceHttpObservationConvention();
      ServerHttpObservationFilter filter =
          new ServerHttpObservationFilter(observations, convention);
      record(filter, 503, true);
      record(filter, 500, false);
      record(filter, 200, false);
      long planned = registry
          .get("http.server.requests")
          .tags("maintenance", "planned", "status", "503")
          .timer()
          .count();
      long failed = registry
          .get("http.server.requests")
          .tags("maintenance", "none", "status", "500")
          .timer()
          .count();
      long successful = registry
          .get("http.server.requests")
          .tags("maintenance", "none", "status", "200")
          .timer()
          .count();
      assertThat(planned).isEqualTo(1);
      assertThat(failed).isEqualTo(1);
      assertThat(successful).isEqualTo(1);
    } finally {
      registry.close();
    }
  }

  private void record(ServerHttpObservationFilter filter, int status, boolean planned)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (incoming, outgoing) -> {
      if (planned) {
        incoming.setAttribute(MaintenanceHttpObservationConvention.PLANNED_ATTRIBUTE, Boolean.TRUE);
      }
      response.setStatus(status);
    });
  }
}
