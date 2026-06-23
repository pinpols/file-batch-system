package io.github.pinpols.batch.ext.sample.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ADR-035 P1.6 — Spring Boot 租户自托管 worker 示范入口。
 *
 * <p>对照纯 Java 版 {@code SampleTenantWorker}:这里<b>没有任何 wiring 代码</b>。
 * {@code batch-worker-sdk-spring-boot-starter} 会自动:
 *
 * <ul>
 *   <li>从 {@code application.yml} 的 {@code batch.worker-sdk.*} 绑定出 {@code BatchPlatformClientConfig}
 *   <li>收集容器内所有 {@code SdkTaskHandler} bean(本例的 {@code EchoHandler} / {@code SleepHandler})自动注册
 *   <li>用 {@code SmartLifecycle} 在应用启动时 {@code start()}、关闭时 {@code stop()}
 * </ul>
 *
 * <p>register 失败(平台不可达 / apiKey 失效 / 零 handler)会让应用启动失败,符合 K8s / systemd 重启预期。
 */
@SpringBootApplication
public class SampleSpringWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(SampleSpringWorkerApplication.class, args);
  }
}
