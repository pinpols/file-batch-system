package com.example.batch.common.i18n;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 读路径渲染器:把持久化的 {@code (error_key, error_args, error_message)} 三元组按当前 Locale 渲染成字符串。
 *
 * <p>策略:
 *
 * <ol>
 *   <li>{@code error_key} 非空 → {@code messageSource} 用当前 Locale + parsed args 重渲染
 *   <li>资源未找到 / 渲染结果为空 → 回退到 {@code error_message}
 *   <li>{@code error_key} 为空 → 直接返回 {@code error_message}(老 literal / 第三方异常)
 * </ol>
 *
 * <p>callsite 可以传入 db 行的三个值,无需自行组装异常。直接走 {@link MessageSource} 而非 {@link BizMessageResolver},因为
 * resolver 在 NoSuchMessage 时会回退到 ResultCode.label(),不适合持久化场景的 "key 未注册时回到原始 message" 语义。
 *
 * <p>由 {@link BatchI18nAutoConfiguration} 装配为 bean, 与 {@link BizMessageResolver} 同款理由:
 * 不依赖业务模块 @ComponentScan 覆盖 com.example.batch.common.i18n。
 */
@Slf4j
public class LocalizedErrorRenderer {

  private final MessageSource messageSource;
  private final ObjectMapper objectMapper;

  public LocalizedErrorRenderer(MessageSource messageSource, ObjectMapper objectMapper) {
    this.messageSource = messageSource;
    this.objectMapper = objectMapper;
  }

  /** 用当前请求的 {@link LocaleContextHolder#getLocale()} 渲染。 */
  public String render(String errorKey, String errorArgsJson, String fallback) {
    return render(errorKey, errorArgsJson, fallback, LocaleContextHolder.getLocale());
  }

  public String render(String errorKey, String errorArgsJson, String fallback, Locale locale) {
    if (errorKey == null || errorKey.isBlank()) {
      return fallback;
    }
    Object[] args = BizExceptionUtils.parseArgs(errorArgsJson, objectMapper);
    try {
      String rendered = messageSource.getMessage(errorKey, args, locale);
      return (rendered == null || rendered.isBlank()) ? fallback : rendered;
    } catch (NoSuchMessageException ignored) {
      SwallowedExceptionLogger.info(
          LocalizedErrorRenderer.class, "catch:NoSuchMessageException", ignored);

      return fallback;
    } catch (RuntimeException ex) {
      // v6 hardening: 兜底 IllegalArgumentException 等渲染异常——
      // 当 error_args JSONB 损坏导致 parseArgs 返回空数组、message 模板含 {N} 占位符时，
      // Spring 内部 MessageFormat 会抛 IllegalArgumentException；旧版仅 catch NoSuchMessageException
      // 让异常透传到 5xx，影响 console 列表查询。改为 fallback + warn 让历史脏数据不影响读路径。
      log.warn(
          "localized error render failed; falling back to raw message: errorKey={}, locale={},"
              + " cause={}",
          errorKey,
          locale,
          ex.getMessage());
      return fallback;
    }
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

  /** carrier 直渲染:从 entity / DTO / Mapper Param 上的 errorKey/errorArgs/errorMessage 三字段直接读。 */
  public String render(LocalizedErrorCarrier carrier) {
    return carrier == null ? null : render(carrier.toLocalizedError());
  }

  public String render(LocalizedErrorCarrier carrier, Locale locale) {
    return carrier == null ? null : render(carrier.toLocalizedError(), locale);
  }
}
