package io.github.pinpols.batch.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** {@link LocalizedErrorRenderer} 读路径 + {@link BizExceptionUtils} 写路径互转测试。 */
@DisplayName("本地化错误渲染与异常转换:渲染, 兜底, 截断与读写往返")
class LocalizedErrorRendererTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ResourceBundleMessageSource messageSource = messageSource();
  private final BizMessageResolver resolver = new BizMessageResolver(messageSource);
  private final LocalizedErrorRenderer renderer =
      new LocalizedErrorRenderer(messageSource, objectMapper);

  @Test
  @DisplayName("带消息键与参数的渲染:按语言取译文并填充占位符")
  void shouldResolvePerLocale_whenKeyAndArgsPresent() {
    String rendered = renderer.render(
        "error.tenant.already_exists",
        "[\"acme\"]",
        "stale message in storage",
        Locale.SIMPLIFIED_CHINESE);

    assertThat(rendered).isEqualTo("租户已存在:acme");
  }

  @Test
  @DisplayName("带消息键的渲染:英文语言环境下返回英文译文")
  void shouldResolveEnglish_whenKeyPresent() {
    String rendered =
        renderer.render("error.tenant.already_exists", "[\"acme\"]", "fallback", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("tenant already exists: acme");
  }

  @Test
  @DisplayName("消息键缺失:直接返回存储的兜底文案")
  void shouldReturnFallback_whenKeyAbsent() {
    String rendered = renderer.render(null, null, "literal message", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("literal message");
  }

  @Test
  @DisplayName("消息键在资源中不存在:回退到存储的兜底文案")
  void shouldFallBackToStoredMessage_whenKeyUnknown() {
    String rendered =
        renderer.render("error.does.not.exist", "[\"x\"]", "fallback message", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("fallback message");
  }

  @Test
  @DisplayName("业务异常转换:保留消息键与参数串, 并渲染出可读文案")
  void shouldExtractKeyAndArgs_whenBusinessExceptionGiven() {
    BizException ex = BizException.of(ResultCode.NOT_FOUND, "error.tenant.already_exists", "acme");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isEqualTo("error.tenant.already_exists");
    assertThat(error.argsJson()).isEqualTo("[\"acme\"]");
    assertThat(error.renderedMessage()).contains("acme");
  }

  @Test
  @DisplayName("旧式业务异常:无消息键与参数, 可读文案即原始信息")
  void shouldKeepLiteralMessage_whenLegacyBusinessException() {
    BizException ex = new BizException(ResultCode.SYSTEM_ERROR, "db connection refused");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isNull();
    assertThat(error.argsJson()).isNull();
    assertThat(error.renderedMessage()).isEqualTo("db connection refused");
  }

  @Test
  @DisplayName("第三方异常:无消息键与参数, 可读文案取异常描述")
  void shouldKeepMessage_whenThirdPartyThrowable() {
    RuntimeException ex = new RuntimeException("kafka producer failed");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isNull();
    assertThat(error.argsJson()).isNull();
    assertThat(error.renderedMessage()).isEqualTo("kafka producer failed");
  }

  @Test
  @DisplayName("转换后再次渲染:与首次渲染结果一致, 构成读写往返")
  void shouldRoundTripThroughStorage_whenBusinessException() {
    BizException ex = BizException.of(ResultCode.NOT_FOUND, "error.workflow.not_found", "wf-42");
    LocalizedError stored = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    String rendered = renderer.render(stored, Locale.SIMPLIFIED_CHINESE);

    assertThat(rendered).isEqualTo("工作流不存在:wf-42");
  }

  // ─── v6 hardening ──────────────────────────────────────────────────────────

  /** P1-1：render 不应在 args 与占位符不匹配时把 IllegalArgumentException 透传到上层。 */
  @Test
  @DisplayName("参数串损坏:渲染不抛异常, 至少返回非空文案")
  void shouldNotThrow_whenArgsJsonCorrupt() {
    // error.tenant.already_exists 模板含 {0}，但 errorArgsJson 损坏 → parseArgs 返回空数组 →
    // MessageFormat 尝试替换 {0} 时抛 IllegalArgumentException；renderer 应 fallback
    String rendered = renderer.render(
        "error.tenant.already_exists",
        "<<<not valid json>>>",
        "fallback for corrupt args",
        Locale.ENGLISH);

    // 损坏 args 解析为空数组后，MessageFormat 行为可能因 Spring 版本而异；
    // 关键约束是不抛异常，且至少能返回某个字符串（fallback 或 raw template）
    assertThat(rendered).isNotNull();
  }

  @Test
  @DisplayName("渲染过程抛出运行时异常:吞掉异常并返回兜底文案")
  void shouldFallBack_whenMessageSourceThrows() {
    // 守护"render 任何 RuntimeException 都 fallback"的契约：注入一个永远抛 IAE 的 messageSource，
    // 模拟 args 与占位符不匹配 / args 类型异常等渲染失败场景
    org.springframework.context.MessageSource faulty =
        new org.springframework.context.support.AbstractMessageSource() {
          @Override
          protected java.text.MessageFormat resolveCode(String code, Locale locale) {
            throw new IllegalArgumentException("simulated MessageFormat failure");
          }
        };
    LocalizedErrorRenderer faultyRenderer = new LocalizedErrorRenderer(faulty, objectMapper);

    String rendered =
        faultyRenderer.render("any.key", "[]", "fallback when broken", Locale.ENGLISH);

    assertThat(rendered).isEqualTo("fallback when broken");
  }

  /** P1-2：写入路径 truncate error_message 防 VARCHAR(1024) 超长。 */
  @Test
  @DisplayName("超长可读文案:按存储长度上限截断并追加截断标记")
  void shouldTruncate_whenRenderedMessageExceedsLimit() {
    String longText = "x".repeat(2000);
    BizException ex = new BizException(ResultCode.SYSTEM_ERROR, longText);

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.renderedMessage()).hasSizeLessThanOrEqualTo(1024);
    assertThat(error.renderedMessage()).endsWith("…[truncated]");
  }

  @Test
  @DisplayName("短文案:不触发截断, 原样保留")
  void shouldKeepShortMessageUnchanged() {
    BizException ex = new BizException(ResultCode.SYSTEM_ERROR, "short message");

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.renderedMessage()).isEqualTo("short message");
  }

  @Test
  @DisplayName("第三方异常超长描述:同样按存储长度上限截断")
  void shouldTruncate_whenThirdPartyMessageExceedsLimit() {
    RuntimeException ex = new RuntimeException("y".repeat(3000));

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.renderedMessage()).hasSizeLessThanOrEqualTo(1024);
  }

  /** P3：args 含复杂对象不阻塞写入数据库（仅 log.warn）；JSON 序列化仍能完成。 */
  @Test
  @DisplayName("参数为复杂对象:仍能完成转换与序列化, 不抛异常")
  void shouldSerializeComplexArgsWithoutThrowing() {
    BizException ex = BizException.of(
        ResultCode.NOT_FOUND, "error.tenant.already_exists", java.util.Map.of("nested", "value"));

    LocalizedError error = BizExceptionUtils.toLocalizedError(ex, resolver, objectMapper);

    assertThat(error.key()).isEqualTo("error.tenant.already_exists");
    assertThat(error.argsJson()).isNotNull(); // 复杂对象仍能序列化
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
