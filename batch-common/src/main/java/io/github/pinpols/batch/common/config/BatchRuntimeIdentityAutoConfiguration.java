package io.github.pinpols.batch.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 为 {@link ApplicationNameProvider} / {@link RuntimeInfrastructureInspector} 提供 AutoConfiguration 回退。
 *
 * <p>现有模块通过 {@code @ComponentScan("io.github.pinpols.batch.common")} 直接拿到这两个 {@code @Component}
 * 注册；但嵌入式测试上下文（例如 batch-e2e-tests 的 {@code E2e*Application}）出于隔离需要不扫 common 包，这里靠
 * {@link ConditionalOnMissingBean} 在 bean 缺失时回退创建，两条路径互不冲突（与 {@link
 * BatchTimezoneAutoConfiguration} 同一约定）。
 *
 * <p>二者由启动守护构造注入：orchestrator 的 {@code QuotaRuntimeBackendGuard}、worker-core 的 {@code
 * WorkerReportOutboxBackendGuard} 与 {@code WorkerDataSourceSupport}。缺任一都会让上下文启动失败——E2E 窄切片曾因此
 * 整个 Full Gate 红（4 个 shard 全挂）。
 */
@AutoConfiguration
public class BatchRuntimeIdentityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ApplicationNameProvider applicationNameProvider(Environment environment) {
    return new ApplicationNameProvider(environment);
  }

  @Bean
  @ConditionalOnMissingBean
  public RuntimeInfrastructureInspector runtimeInfrastructureInspector(Environment environment) {
    return new RuntimeInfrastructureInspector(environment);
  }
}
