package com.example.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** {@link BizMessageResolver} 五条路径回归测试。 */
class BizMessageResolverTest {

  private final BizMessageResolver resolver = new BizMessageResolver(messageSource());

  @Test
  void resolve_messageKey_hits_resource() {
    BizException ex = BizException.of(ResultCode.NOT_FOUND, "error.tenant.already_exists", "acme");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE)).isEqualTo("租户已存在:acme");
    assertThat(resolver.resolve(ex, Locale.ENGLISH)).isEqualTo("tenant already exists: acme");
  }

  @Test
  void resolve_messageKey_missing_falls_back_to_literal_message() {
    // messageKey 写错 / 资源不存在,回退到 super.message(本例 = key 本身,
    // BizMessageResolver 检测 literal == key 时进一步回退到 ResultCode.label())
    BizException ex = BizException.of(ResultCode.SYSTEM_ERROR, "error.does.not.exist");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo(ResultCode.SYSTEM_ERROR.label());
  }

  @Test
  void resolve_legacy_literal_passthrough() {
    // 老 (code, message) 构造器:messageKey=null,直接透出 message
    BizException ex = new BizException(ResultCode.INVALID_ARGUMENT, "动态错误信息:foo");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE)).isEqualTo("动态错误信息:foo");
  }

  @Test
  void resolve_resultCode_returns_common_code_label() {
    assertThat(resolver.resolve(ResultCode.RATE_LIMITED, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo("请求过于频繁");
    assertThat(resolver.resolve(ResultCode.RATE_LIMITED, Locale.ENGLISH))
        .isEqualTo("too many requests");
  }

  @Test
  void resolve_resultCode_missing_falls_back_to_label() {
    // 用一个故意不存在的 ResultCode key — 走 ResourceBundleMessageSource fallback 到 ResultCode.label()
    BizMessageResolver isolatedResolver = new BizMessageResolver(emptyMessageSource());

    assertThat(isolatedResolver.resolve(ResultCode.NOT_FOUND, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo(ResultCode.NOT_FOUND.label());
  }

  @Test
  void resolve_null_returns_null() {
    assertThat(resolver.resolve((ResultCode) null)).isNull();
    assertThat(resolver.resolve((ResultCode) null, Locale.ENGLISH)).isNull();
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

  /** 空 messageSource:任何 key 都查不到,模拟"资源文件未注册"场景。 */
  private static ResourceBundleMessageSource emptyMessageSource() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("nonexistent_messages_basename_for_test");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    source.setUseCodeAsDefaultMessage(false);
    return source;
  }
}
