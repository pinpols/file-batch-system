package io.github.pinpols.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;

@DisplayName("国际化自动配置:消息源优先级与语言回退")
class BatchI18nAutoConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          MessageSourceAutoConfiguration.class, BatchI18nAutoConfiguration.class))
      .withBean(ObjectMapper.class, ObjectMapper::new)
      .withPropertyValues("spring.messages.fallback-to-system-locale=false");

  @Test
  @DisplayName("默认消息源不受系统语言影响并分别提供英文和中文")
  void shouldHonorConfiguredLocaleFallback() {
    runner.run(context -> {
      MessageSource messages = context.getBean(MessageSource.class);
      assertThat(messages).isInstanceOf(ResourceBundleMessageSource.class);
      Object[] args = {"TYPE", "DETAIL"};
      assertThat(messages.getMessage("error.workflow.sensor_probe_failed", args, Locale.US))
          .contains("sensor probe failed")
          .contains("TYPE", "DETAIL");
      assertThat(messages.getMessage(
              "error.workflow.sensor_probe_failed", args, Locale.SIMPLIFIED_CHINESE))
          .contains("探测失败")
          .contains("TYPE", "DETAIL");
    });
  }

  @Test
  @DisplayName("用户明确注册的消息源优先于自动配置")
  void shouldKeepExplicitlyConfiguredMessageSource() {
    StaticMessageSource custom = new StaticMessageSource();
    custom.addMessage("test.key", Locale.US, "custom message");
    runner.withBean("messageSource", StaticMessageSource.class, () -> custom).run(context -> {
      assertThat(context.getBean(MessageSource.class)).isSameAs(custom);
      assertThat(context.getBean(MessageSource.class).getMessage("test.key", null, Locale.US))
          .isEqualTo("custom message");
    });
  }

  @Test
  @DisplayName("应用级显式配置仍可覆盖共享消息回退默认值")
  void shouldAllowExplicitFallbackOverride() {
    runner
        .withPropertyValues("spring.messages.fallback-to-system-locale=true")
        .run(context -> assertThat(
                context.getBean(MessageSourceProperties.class).isFallbackToSystemLocale())
            .isTrue());
  }
}
