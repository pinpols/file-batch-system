package io.github.pinpols.batch.console.config;

import java.util.UUID;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 Console AI 持久化所需的 PostgreSQL UUID 类型映射。 */
@Configuration(proxyBeanMethods = false)
public class ConsoleAiUuidMappingConfiguration {
  @Bean
  ConfigurationCustomizer consoleAiUuidTypeHandler() {
    return configuration ->
        configuration.getTypeHandlerRegistry().register(UUID.class, new PostgresUuidTypeHandler());
  }
}
