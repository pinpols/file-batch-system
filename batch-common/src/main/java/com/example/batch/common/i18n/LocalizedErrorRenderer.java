package com.example.batch.common.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 读路径渲染器:把持久化的 {@code (error_key, error_args, error_message)} 三元组按当前 Locale 渲染成字符串。
 *
 * <p>策略:
 *
 * <ol>
 *   <li>{@code error_key} 非空 → {@link BizMessageResolver} 用当前 Locale + parsed args 重渲染
 *   <li>渲染结果为空 / 等于 key 本身(资源未找到)→ 回退到 {@code error_message}
 *   <li>{@code error_key} 为空 → 直接返回 {@code error_message}(老 literal / 第三方异常)
 * </ol>
 *
 * <p>callsite 可以传入 db 行的三个值,无需自行组装异常。
 */
@Component
public class LocalizedErrorRenderer {

  private final BizMessageResolver resolver;
  private final ObjectMapper objectMapper;

  public LocalizedErrorRenderer(BizMessageResolver resolver, ObjectMapper objectMapper) {
    this.resolver = resolver;
    this.objectMapper = objectMapper;
  }

  /** 用当前请求的 {@link LocaleContextHolder#getLocale()} 渲染。 */
  public String render(String errorKey, String errorArgsJson, String fallback) {
    return render(errorKey, errorArgsJson, fallback, LocaleContextHolder.getLocale());
  }

  public String render(String errorKey, String errorArgsJson, String fallback, Locale locale) {
    Object[] args = BizExceptionUtils.parseArgs(errorArgsJson, objectMapper);
    return BizExceptionUtils.renderOrFallback(resolver, errorKey, args, fallback, locale);
  }

  /** 等价的 record 形态。 */
  public String render(LocalizedError error) {
    if (error == null) {
      return null;
    }
    return render(error.key(), error.argsJson(), error.renderedMessage());
  }

  public String render(LocalizedError error, Locale locale) {
    if (error == null) {
      return null;
    }
    return render(error.key(), error.argsJson(), error.renderedMessage(), locale);
  }
}
