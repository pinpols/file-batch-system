package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.config.ConsoleAsyncConfiguration;
import io.github.pinpols.batch.console.support.http.ConsoleOutboundTransport;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@DisplayName("维护信号发布器随应用启动自动对账")
class MaintenanceAlertPublisherSchedulingTest {

  @Test
  @DisplayName("懒加载下自动发布、解除并在组件销毁后停止")
  void publishesAndResolvesAfterApplicationReady_whenLazyInitializationEnabled() throws Exception {
    Instant now = Instant.now();
    Instant deadline = now.plusSeconds(90);
    AtomicReference<MaintenanceState> state = new AtomicReference<>(new MaintenanceState(
        true, false, "维护", deadline, now.minusSeconds(1), List.of("worker-export"), 1L, now, true));
    MaintenanceStateHolder holder = mock(MaintenanceStateHolder.class);
    when(holder.current()).thenAnswer(invocation -> state.get());
    OutboundHttpTransport transport = mock(OutboundHttpTransport.class);
    when(transport.execute(any())).thenReturn(new OutboundHttpResponse(200, ""));
    AlertmanagerNotifyProperties properties = new AlertmanagerNotifyProperties();
    properties.getSilence().setApiBaseUrl("http://alertmanager.example.invalid:9093");
    properties.getSilence().setMaintenanceScope("test-deployment");
    MeterRegistry registry = new SimpleMeterRegistry();

    new ApplicationContextRunner()
        .withInitializer(context ->
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
        .withUserConfiguration(TestConfiguration.class)
        .withBean(MaintenanceStateHolder.class, () -> holder)
        .withBean(
            OutboundHttpTransport.class,
            () -> transport,
            definition -> ((AbstractBeanDefinition) definition)
                .addQualifier(new AutowireCandidateQualifier(ConsoleOutboundTransport.class)))
        .withBean(AlertmanagerNotifyProperties.class, () -> properties)
        .withBean(MeterRegistry.class, () -> registry)
        .run(context -> {
          assertThat(context).hasNotFailed();
          context.publishEvent(new ApplicationReadyEvent(
              new SpringApplication(),
              new String[0],
              context.getSourceApplicationContext(),
              Duration.ZERO));
          await()
              .atMost(8, TimeUnit.SECONDS)
              .untilAsserted(() -> verify(transport).execute(any()));
          assertThat(registry
                  .find("batch.console.maintenance.alert_suppression_available")
                  .gauge())
              .isNotNull();

          state.set(new MaintenanceState(
              false, false, null, null, null, List.of(), 2L, Instant.now(), true));
          await()
              .atMost(8, TimeUnit.SECONDS)
              .untilAsserted(() -> verify(transport, atLeast(2)).execute(any()));
          ArgumentCaptor<OutboundHttpRequest> requests =
              ArgumentCaptor.forClass(OutboundHttpRequest.class);
          verify(transport, atLeast(2)).execute(requests.capture());
          ObjectMapper objectMapper = new ObjectMapper();
          List<OutboundHttpRequest> calls = requests.getAllValues();
          JsonNode first = objectMapper.readTree(calls.get(0).body());
          JsonNode last = objectMapper.readTree(calls.get(calls.size() - 1).body());
          JsonNode initialExport = signalFor(first, "batch-worker-export");
          JsonNode resolvedExport = signalFor(last, "batch-worker-export");
          assertThat(Instant.parse(initialExport.path("endsAt").asText())).isEqualTo(deadline);
          assertThat(Instant.parse(resolvedExport.path("endsAt").asText()))
              .isBeforeOrEqualTo(Instant.now());

          MaintenanceAlertPublisher publisher = context.getBean(MaintenanceAlertPublisher.class);
          context.getAutowireCapableBeanFactory().destroyBean(publisher);
          state.set(new MaintenanceState(
              true,
              false,
              "维护",
              deadline,
              now.minusSeconds(1),
              List.of("worker-export"),
              3L,
              Instant.now(),
              true));
          await()
              .during(6, TimeUnit.SECONDS)
              .atMost(8, TimeUnit.SECONDS)
              .untilAsserted(() -> verify(transport, times(2)).execute(any()));
        });
  }

  private static JsonNode signalFor(JsonNode signals, String job) {
    for (JsonNode signal : signals) {
      if (job.equals(signal.path("labels").path("job").asText())) {
        return signal;
      }
    }
    throw new AssertionError("缺少服务信号：" + job);
  }

  @Configuration(proxyBeanMethods = false)
  @Import({ConsoleAsyncConfiguration.class, MaintenanceAlertPublisher.class})
  static class TestConfiguration {}
}
