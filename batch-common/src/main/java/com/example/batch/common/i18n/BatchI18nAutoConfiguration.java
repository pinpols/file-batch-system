package com.example.batch.common.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * i18n 基础设施:把 BizException + ResultCode + ResponseFactory 链路上的英文 free text 翻译成 Accept-Language
 * 协商出来的语种。前端不发 Accept-Language 时默认中文(zh_CN),与产品默认面向中文用户保持一致。
 *
 * <p>资源文件位置:`messages.properties`(默认 fallback,英文)+ `messages_zh_CN.properties`(中文)。命名 key 形如
 * `error.<scope>.<reason>`,占位符用 {0}/{1}/...,与 BizException 构造器的 args 顺序一致。
 */
@AutoConfiguration
public class BatchI18nAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BizMessageResolver bizMessageResolver(MessageSource messageSource) {
    return new BizMessageResolver(messageSource);
  }

  @Bean
  @ConditionalOnMissingBean
  public LocalizedErrorRenderer localizedErrorRenderer(
      MessageSource messageSource, ObjectMapper objectMapper) {
    return new LocalizedErrorRenderer(messageSource, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(name = "messageSource")
  public MessageSource messageSource() {
    ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
    source.setBasename("classpath:messages");
    // R7-A5-P2: 走 StandardCharsets 常量来源，避免字面量 "UTF-8"（CLAUDE.md §字符编码）。
    source.setDefaultEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
    source.setFallbackToSystemLocale(false);
    // useCodeAsDefaultMessage=false:key 不存在时返回 null,让 ExceptionHandler 走 fallback 字面量,
    // 不会把 key 字符串当 message 暴露给前端。
    source.setUseCodeAsDefaultMessage(false);
    source.setCacheSeconds(60);
    return source;
  }

  /**
   * 默认 Accept-Language fallback 到 zh_CN。前端发 `Accept-Language: en` / `en-US` 等时,Spring 自动按 basename
   * 找 messages_en.properties / 默认 messages.properties。
   */
  @Bean
  @ConditionalOnWebApplication
  @ConditionalOnMissingBean(LocaleResolver.class)
  public LocaleResolver localeResolver() {
    AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
    resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
    return resolver;
  }
}
