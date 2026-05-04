package org.springframework.boot.autoconfigure.web.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnBean(JsonMapper.class)
public class RestClientAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public RestClient.Builder restClientBuilder(JsonMapper jsonMapper) {
    return RestClient.builder()
        .configureMessageConverters(
            b ->
                b.configureMessageConvertersList(
                    converters ->
                        converters.add(0, new JacksonJsonHttpMessageConverter(jsonMapper))));
  }
}
