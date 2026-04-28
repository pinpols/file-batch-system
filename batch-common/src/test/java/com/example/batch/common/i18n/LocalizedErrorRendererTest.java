package com.example.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** {@link LocalizedErrorRenderer} 读路径 + {@link BizExceptionUtils} 写路径互转测试。 */
class LocalizedErrorRendererTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ResourceBundleMessageSource messageSource = messageSource();
  private final BizMessageResolver resolver = new BizMessageResolver(messageSource);
  private final LocalizedErrorRenderer renderer =
      new LocalizedErrorRenderer(messageSource, objectMapper);

  @Test
  void render_with_key_and_args_resolves_per_locale() {
    String rendered =
        renderer.render(
            "error.tenant.already_exists",
            "[\"acme\"]",
            "stale message in storage",
            Locale.SIMPLIFIED_CHINESE);

    assertThat(rendered).isEqualTo("租户已存在:acme");
  }

  @Test
  void render_with_key_resolves_english() {
    String rendered =
        renderer.render("error.tenant.already_exists", "[\"acme\"]", "fallback", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("tenant already exists: acme");
  }

  @Test
  void render_without_key_returns_fallback() {
    String rendered = renderer.render(null, null, "literal message", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("literal message");
  }

  @Test
  void render_with_unknown_key_falls_back_to_message() {
    String rendered =
        renderer.render("error.does.not.exist", "[\"x\"]", "fallback message", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("fallback message");
  }

  @Test
  void biz_exception_utils_extracts_key_and_args() {
    BizException ex = BizException.of(ResultCode.NOT_FOUND, "error.tenant.already_exists", "acme");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isEqualTo("error.tenant.already_exists");
    assertThat(error.argsJson()).isEqualTo("[\"acme\"]");
    assertThat(error.renderedMessage()).contains("acme");
  }

  @Test
  void biz_exception_utils_handles_legacy_literal() {
    BizException ex = new BizException(ResultCode.SYSTEM_ERROR, "db connection refused");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isNull();
    assertThat(error.argsJson()).isNull();
    assertThat(error.renderedMessage()).isEqualTo("db connection refused");
  }

  @Test
  void biz_exception_utils_handles_third_party_throwable() {
    RuntimeException ex = new RuntimeException("kafka producer failed");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isNull();
    assertThat(error.argsJson()).isNull();
    assertThat(error.renderedMessage()).isEqualTo("kafka producer failed");
  }

  @Test
  void biz_exception_utils_round_trip() {
    BizException ex = BizException.of(ResultCode.NOT_FOUND, "error.workflow.not_found", "wf-42");
    LocalizedError stored = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    String rendered = renderer.render(stored, Locale.SIMPLIFIED_CHINESE);

    assertThat(rendered).isEqualTo("工作流不存在:wf-42");
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  private static ResourceBundleMessageSource messageSource() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    source.setUseCodeAsDefaultMessage(false);
    return source;
  }
}
