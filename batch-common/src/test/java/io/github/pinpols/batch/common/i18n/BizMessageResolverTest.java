package io.github.pinpols.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.SensitiveDataValidator;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** {@link BizMessageResolver} 五条路径回归测试。 */
@DisplayName("异常消息本地化解析:资源命中, 兜底, 空值与旧式构造的返回约定")
class BizMessageResolverTest {

  private final BizMessageResolver resolver = new BizMessageResolver(messageSource());

  @Test
  @DisplayName("普通节点汇聚错误在中英文中均按节点而非网关描述")
  void shouldLocalizeJoinErrorsForAnyNode() {
    BizException mode = BizException.of(
        ResultCode.VALIDATION_ERROR, "error.workflow.dag.gateway_join_mode_missing", "JOB_A");
    BizException threshold = BizException.of(
        ResultCode.VALIDATION_ERROR,
        "error.workflow.dag.gateway_join_threshold_invalid",
        "JOB_A",
        3,
        2);
    assertThat(resolver.resolve(mode, Locale.ENGLISH))
        .contains("Node JOB_A", "ALL")
        .doesNotContain("GATEWAY");
    assertThat(resolver.resolve(mode, Locale.SIMPLIFIED_CHINESE))
        .contains("节点 JOB_A", "ALL")
        .doesNotContain("GATEWAY");
    assertThat(resolver.resolve(threshold, Locale.ENGLISH))
        .contains("Node JOB_A", "3", "2")
        .doesNotContain("GATEWAY");
    assertThat(resolver.resolve(threshold, Locale.SIMPLIFIED_CHINESE))
        .contains("节点 JOB_A", "3", "2")
        .doesNotContain("GATEWAY");
  }

  @Test
  @DisplayName("复制源目标重合使用对应语言资源，不回退到通用错误")
  void shouldLocalizeOverlappingConfigurationCopyTargets() {
    BizException failure =
        BizException.of(ResultCode.INVALID_ARGUMENT, "error.config_copy.source_target_overlap");
    assertThat(resolver.resolve(failure, Locale.SIMPLIFIED_CHINESE)).isEqualTo("源租户不能同时作为复制目标。");
    assertThat(resolver.resolve(failure, Locale.ENGLISH))
        .isEqualTo("The source tenant cannot also be a copy target.");
  }

  @Test
  @DisplayName("消息键命中资源文件:按语言返回对应译文并填充参数")
  void resolve_messageKey_hits_resource() {
    BizException ex = BizException.of(ResultCode.NOT_FOUND, "error.tenant.already_exists", "acme");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE)).isEqualTo("租户已存在:acme");
    assertThat(resolver.resolve(ex, Locale.ENGLISH)).isEqualTo("tenant already exists: acme");
  }

  @Test
  @DisplayName("敏感字段名含凭据时，中英文错误文案均不回显原字段名")
  void shouldNotRenderCredentialEmbeddedInSensitiveKey() {
    assertThatExceptionOfType(BizException.class)
        .isThrownBy(() -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(
            Map.of("token=probe-secret", "value"), "ctx.scope"))
        .satisfies(error -> {
          assertThat(resolver.resolve(error, Locale.SIMPLIFIED_CHINESE))
              .contains("ctx.scope")
              .doesNotContain("probe-secret");
          assertThat(resolver.resolve(error, Locale.ENGLISH))
              .contains("ctx.scope")
              .doesNotContain("probe-secret");
        });
  }

  @Test
  @DisplayName("派发业务错误:渲染结果拼接业务码与可读信息, 且两种语言一致")
  void shouldRenderCodeAndHumanMessage_whenDispatchBusinessError() {
    BizException ex = BizException.of(
        ResultCode.BUSINESS_ERROR,
        "error.partition.dispatch_business_error",
        "POOL_EXHAUSTED",
        "worker pool exhausted");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo("[POOL_EXHAUSTED] worker pool exhausted");
    assertThat(resolver.resolve(ex, Locale.ENGLISH))
        .isEqualTo("[POOL_EXHAUSTED] worker pool exhausted");
  }

  @Test
  @DisplayName("消息键在资源中缺失:回退到通用结果码的默认文案")
  void resolve_messageKey_missing_falls_back_to_literal_message() {
    // messageKey 写错 / 资源不存在,回退到 super.message(本例 = key 本身,
    // BizMessageResolver 检测 literal == key 时进一步回退到 ResultCode.label())
    BizException ex = BizException.of(ResultCode.SYSTEM_ERROR, "error.does.not.exist");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo(ResultCode.SYSTEM_ERROR.label());
  }

  @Test
  @DisplayName("旧式构造传入的完整文案:不做键解析, 原样透出")
  void shouldPassThroughLiteralMessage_whenLegacyConstruction() {
    // 老 (code, message) 构造器:messageKey=null,直接透出 message
    BizException ex = new BizException(ResultCode.INVALID_ARGUMENT, "动态错误信息:foo");

    assertThat(resolver.resolve(ex, Locale.SIMPLIFIED_CHINESE)).isEqualTo("动态错误信息:foo");
  }

  @Test
  @DisplayName("结果码解析:按语言返回通用码文案")
  void resolve_resultCode_returns_common_code_label() {
    assertThat(resolver.resolve(ResultCode.RATE_LIMITED, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo("请求过于频繁");
    assertThat(resolver.resolve(ResultCode.RATE_LIMITED, Locale.ENGLISH))
        .isEqualTo("too many requests");
  }

  @Test
  @DisplayName("资源中无该结果码条目:回退到结果码自带默认文案")
  void resolve_resultCode_missing_falls_back_to_label() {
    // 用一个故意不存在的 ResultCode key — 走 ResourceBundleMessageSource fallback 到 ResultCode.label()
    BizMessageResolver isolatedResolver = new BizMessageResolver(emptyMessageSource());

    assertThat(isolatedResolver.resolve(ResultCode.NOT_FOUND, Locale.SIMPLIFIED_CHINESE))
        .isEqualTo(ResultCode.NOT_FOUND.label());
  }

  @Test
  @DisplayName("结果码为空:两种语言下均返回空值")
  void shouldReturnNull_whenResultCodeIsNull() {
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
