package io.github.pinpols.batch.console.config;

import java.util.UUID;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConsoleAiUuidMappingConfiguration {
  @Bean
  ConfigurationCustomizer consoleAiUuidTypeHandler() {
    return configuration ->
        configuration.getTypeHandlerRegistry().register(UUID.class, new PostgresUuidTypeHandler());
  }
}
