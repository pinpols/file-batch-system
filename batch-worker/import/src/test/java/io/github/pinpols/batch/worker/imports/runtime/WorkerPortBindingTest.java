package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.config.WorkerRegistryStartupProperties;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.context.ServerPortInfoApplicationContextInitializer;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.support.TestPropertySourceUtils;

@DisplayName("Worker 注册使用真实绑定端口, 不混用独立管理端口")
class WorkerPortBindingTest {

  @Test
  @DisplayName("主服务与管理服务均绑定随机端口, Worker 只注册主服务端口")
  void shouldRegisterMainPortWithSeparateManagementServer() {
    try (AnnotationConfigServletWebServerApplicationContext main = webContext()) {
      WorkerLifecycleManager lifecycle = mock(WorkerLifecycleManager.class);
      when(lifecycle.start(any())).thenAnswer(invocation -> invocation.getArgument(0));
      main.registerBean(WorkerLifecycleManager.class, () -> lifecycle);
      main.register(WorkerConfiguration.class);
      main.refresh();

      try (AnnotationConfigServletWebServerApplicationContext management = webContext()) {
        management.setParent(main);
        management.setServerNamespace("management");
        management.refresh();

        int mainPort = main.getWebServer().getPort();
        int managementPort = management.getWebServer().getPort();
        assertThat(mainPort).isPositive().isNotEqualTo(managementPort);
        assertThat(main.getEnvironment().getProperty("server.port", Integer.class))
            .isZero();
        assertThat(main.getEnvironment().getProperty("local.server.port", Integer.class))
            .isEqualTo(mainPort);
        assertThat(main.getEnvironment().getProperty("local.management.port", Integer.class))
            .isEqualTo(managementPort);
        assertThat(main.getBean(ImportWorkerLoop.class).ensureStarted().getPort())
            .isEqualTo(mainPort);
      }
    }
  }

  private static AnnotationConfigServletWebServerApplicationContext webContext() {
    AnnotationConfigServletWebServerApplicationContext context =
        new AnnotationConfigServletWebServerApplicationContext();
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "server.port=0");
    new ServerPortInfoApplicationContextInitializer().initialize(context);
    context.register(WebConfiguration.class);
    return context;
  }

  @Configuration(proxyBeanMethods = false)
  static class WebConfiguration {
    @Bean
    ServletWebServerFactory servletWebServerFactory() {
      return new TomcatServletWebServerFactory(0);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class WorkerConfiguration {
    @Bean
    ImportWorkerLoop importWorkerLoop(WorkerLifecycleManager lifecycle) {
      BatchDateTimeSupport dateTime = new BatchDateTimeSupport(
          Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
      ImportWorkerConfiguration configuration = new ImportWorkerConfiguration(
          "IMPORT-TEST",
          "IMPORT",
          "ta",
          15_000L,
          "test-topic",
          "test-group",
          List.of(),
          null,
          false);
      return new ImportWorkerLoop(
          lifecycle,
          mock(HeartbeatService.class),
          dateTime,
          configuration,
          new WorkerIdentityProperties(),
          new WorkerRegistryStartupProperties(),
          new WorkerConcurrencyProperties());
    }
  }
}
