package com.example.batch.common.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 项目内 {@link RestClient.Builder} 共享 Bean — 在 Spring Boot 4 没出厂提供 {@code
 * RestClientAutoConfiguration} 的环境下补一个最小实现，确保所有模块拿到的 builder 都把 {@link
 * JacksonJsonHttpMessageConverter} 放在 message-converter 第 0 位（保证 JSON 编解码用项目统一的 {@link
 * JsonMapper}）。
 *
 * <p>P2-10 (audit 2026-05-14)：本类先前置于 {@code org.springframework.boot.autoconfigure.web.client} 包下抢
 * Spring 命名空间，Spring Boot 升级时可能与官方 FQCN 冲突。已迁回项目自有 package；通过 {@code
 * META-INF/spring/.../AutoConfiguration.imports} 注册触发。
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnBean(JsonMapper.class)
public class BatchRestClientAutoConfiguration {

  /**
   * R6 audit 2026-05-15：必须 {@code @Scope("prototype")}。Spring 7.0 {@code
   * DefaultRestClientBuilder.baseUrl()} 等方法是 mutate-in-place（{@code this.baseUrl = ...; return
   * this}）， 不是 clone-and-modify。如果按 singleton 注入，14+ 调用方共享同一个 builder 实例， 任何一方调 {@code
   * restClientBuilder.baseUrl(X).build()} 都会把 baseUrl 改写到所有人看到的实例上 —— 高并发下 baseUrl 互相覆盖，请求被发到错误
   * host。
   *
   * <p>prototype scope 让每次注入或 {@code getBean()} 都拿到新 builder 实例；message converter 配置 在工厂方法里独立 apply
   * 到每个新实例，与并发隔离。
   */
  @Bean
  @ConditionalOnMissingBean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  public RestClient.Builder restClientBuilder(JsonMapper jsonMapper) {
    return RestClient.builder()
        .configureMessageConverters(
            b ->
                b.configureMessageConvertersList(
                    converters ->
                        converters.add(0, new JacksonJsonHttpMessageConverter(jsonMapper))));
  }
}
