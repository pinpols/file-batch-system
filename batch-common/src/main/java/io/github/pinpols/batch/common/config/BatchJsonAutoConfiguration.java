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
/** 注册批处理平台统一的 Jackson 序列化约定。 */
public class BatchJsonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper objectMapper(BatchTimezoneProvider timezoneProvider) {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    // 用 FlexibleInstantDeserializer 覆盖默认 InstantDeserializer：
    // 前端发 naive datetime / date-only 字符串时按平台默认时区回退解析，避免 400
    // 时间与日期输入约定见 docs/design/time-date-semantics.md。
    SimpleModule flexibleInstant = new SimpleModule("FlexibleInstantModule");
    flexibleInstant.addDeserializer(
        Instant.class, new FlexibleInstantDeserializer(timezoneProvider));
    mapper.registerModule(flexibleInstant);
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }
}
