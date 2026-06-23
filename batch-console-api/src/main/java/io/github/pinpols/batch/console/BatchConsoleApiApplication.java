package io.github.pinpols.batch.console;

import io.github.pinpols.batch.common.config.BatchJsonAutoConfiguration;
import io.github.pinpols.batch.common.config.BatchObjectCryptoAutoConfiguration;
import io.github.pinpols.batch.common.logging.HttpRequestMdcAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;

@SpringBootApplication(
    scanBasePackages = {"io.github.pinpols.batch.console", "io.github.pinpols.batch.common"},
    exclude = {
      DataRedisRepositoriesAutoConfiguration.class,
      HttpRequestMdcAutoConfiguration.class,
      OpenAiAudioSpeechAutoConfiguration.class,
      OpenAiAudioTranscriptionAutoConfiguration.class,
      OpenAiEmbeddingAutoConfiguration.class,
      OpenAiImageAutoConfiguration.class,
      OpenAiModerationAutoConfiguration.class
    },
    excludeName =
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@ImportAutoConfiguration({
  BatchJsonAutoConfiguration.class,
  BatchObjectCryptoAutoConfiguration.class,
  RestClientAutoConfiguration.class
})
@MapperScan({
  "io.github.pinpols.batch.console.mapper",
  "io.github.pinpols.batch.console.domain.**.mapper",
  "io.github.pinpols.batch.common.mapper"
})
@ConfigurationPropertiesScan(
    basePackages = {"io.github.pinpols.batch.console", "io.github.pinpols.batch.common"})
public class BatchConsoleApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(BatchConsoleApiApplication.class, args);
  }
}
