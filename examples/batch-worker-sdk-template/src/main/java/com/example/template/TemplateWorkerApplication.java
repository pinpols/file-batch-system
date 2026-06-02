package com.example.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * batch-worker-sdk-template 入口 — Round-3 #10 开箱即用骨架。
 *
 * <p>租户 fork 后:
 *
 * <ol>
 *   <li>把 {@code .env.example} 复制成 {@code .env},按注释填 BATCH_BASE_URL / BATCH_TENANT_ID 等
 *   <li>在 {@code handlers/} 下加自己的 {@code SdkTaskHandler}(声明 {@code @Component})
 *   <li>{@code ./run.sh} 或 {@code mvn spring-boot:run} 启动
 * </ol>
 *
 * <p>无需写 wiring:{@code batch-worker-sdk-spring-boot-starter} 自动绑 {@code batch.worker-sdk.*} 配置、
 * 自动注册所有 {@code SdkTaskHandler} bean、SmartLifecycle 托管 start/stop。
 *
 * <p>详见 {@code docs/sdk/onboarding-journey.md}(主仓 docs/sdk/)。
 */
@SpringBootApplication
public class TemplateWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(TemplateWorkerApplication.class, args);
  }
}
