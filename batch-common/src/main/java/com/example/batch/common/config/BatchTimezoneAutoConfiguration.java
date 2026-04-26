package com.example.batch.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 为 {@link BatchTimezoneProvider} 提供 AutoConfiguration 兜底。
 *
 * <p>现有模块通过 {@code @ComponentScan("com.example.batch.common")} 直接拿到 provider 的 {@code @Component}
 * 注册；但嵌入式测试上下文（例如 batch-e2e-tests 的 {@code E2e*Application}）出于隔离需要 不扫 common 包，这里靠 {@link
 * ConditionalOnMissingBean} 在 bean 缺失时兜底创建，两条路径互不冲突。
 */
@AutoConfiguration
@EnableConfigurationProperties(BatchTimezoneProperties.class)
public class BatchTimezoneAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BatchTimezoneProvider batchTimezoneProvider(BatchTimezoneProperties properties) {
    return new BatchTimezoneProvider(properties);
  }
}
