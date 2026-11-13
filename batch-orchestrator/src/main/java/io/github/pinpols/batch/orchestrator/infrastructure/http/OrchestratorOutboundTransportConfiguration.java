package io.github.pinpols.batch.orchestrator.infrastructure.http;

import io.github.pinpols.batch.common.http.OkHttpOutboundHttpTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 Orchestrator 域的出站 HTTP 传输；bean 名称沿用原组件名，限定注解隔离组合应用中的同类实现。 */
@Configuration(proxyBeanMethods = false)
public class OrchestratorOutboundTransportConfiguration {

  @Bean
  @OrchestratorOutboundTransport
  public OkHttpOutboundHttpTransport okHttpOrchestratorExternalHttpTransport() {
    return new OkHttpOutboundHttpTransport();
  }
}
