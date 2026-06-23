package io.github.pinpols.batch.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class BatchJsonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper objectMapper(BatchTimezoneProvider timezoneProvider) {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    // 用 FlexibleInstantDeserializer 覆盖默认 InstantDeserializer：
    // 前端发 naive datetime / date-only 字符串时按平台默认时区回退解析，避免 400
    // (见 docs/analysis/frontend-issue-handoff-2026-05-17.md §5)
    SimpleModule flexibleInstant = new SimpleModule("FlexibleInstantModule");
    flexibleInstant.addDeserializer(
        Instant.class, new FlexibleInstantDeserializer(timezoneProvider));
    mapper.registerModule(flexibleInstant);
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }
}
