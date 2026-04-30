package com.example.batch.common.i18n;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;

/**
 * 各模块 ExceptionHandler 共用的 i18n 解析器:把 BizException(messageKey + args) 翻译成当前 Locale 的文案, 老 literal
 * 异常原样透出。集中在一处避免每个 handler 重复 `messageSource.getMessage(...)` 模板。
 *
 * <p>由 {@link BatchI18nAutoConfiguration} 装配为 bean, 无需依赖 @ComponentScan basePackage 覆盖
 * com.example.batch.common.i18n —— e2e/独立模块只 import 本 auto-config 即拿到。
 */
public class BizMessageResolver {

  private final MessageSource messageSource;

  public BizMessageResolver(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  /**
   * 取 {@link BizException} 的展示字符串。优先级:
   *
   * <ol>
   *   <li>{@code messageKey} 命中资源文件 → 返回翻译后的中文/英文文案
   *   <li>{@code messageKey} 不存在但是 literal 异常 → 返回 {@link BizException#getMessage()}
   *   <li>都没有 → 走 {@link ResultCode#label()}(中文兜底)
   * </ol>
   */
  public String resolve(BizException exception) {
    return resolve(exception, LocaleContextHolder.getLocale());
  }

  public String resolve(BizException exception, Locale locale) {
    String key = exception.getMessageKey();
    if (StringUtils.hasText(key)) {
      try {
        return messageSource.getMessage(key, exception.getMessageArgs(), locale);
      } catch (NoSuchMessageException ignored) {
        // key 配错;继续往下走 fallback 让前端至少有可读消息,避免暴露 raw key。
      }
    }
    String literal = exception.getMessage();
    if (StringUtils.hasText(literal) && !literal.equals(key)) {
      return literal;
    }
    return exception.getCode() == null ? null : exception.getCode().label();
  }

  /** 直接按 ResultCode 解析为通用错误码文案(无业务详情场景用)。 */
  public String resolve(ResultCode code) {
    if (code == null) {
      return null;
    }
    return resolveCommonCode(code, LocaleContextHolder.getLocale());
  }

  public String resolve(ResultCode code, Locale locale) {
    return code == null ? null : resolveCommonCode(code, locale);
  }

  private String resolveCommonCode(ResultCode code, Locale locale) {
    String key = "error.common." + code.code().toLowerCase(Locale.ROOT);
    try {
      return messageSource.getMessage(key, null, locale);
    } catch (NoSuchMessageException ignored) {
      return code.label();
    }
  }
}
