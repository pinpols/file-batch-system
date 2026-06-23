package io.github.pinpols.batch.sdk.idempotent;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A.3 — SpEL-free 幂等键解析:把 {@link Idempotent#key()} 的 {@code {field}} 占位符按上下文求值。
 *
 * <p>解析顺序:{@code {tenantId}} / {@code {jobCode}} / {@code {taskInstanceId}} → 上下文字段; 其余 → {@code
 * ctx.parameters().get(field)}。占位符求不到值 → 抛 {@link IllegalArgumentException}(业务配置错)。
 *
 * <p>不引第三方表达式引擎,仅正则替换,符合 SDK 轻量约束。
 */
final class IdempotencyKeyResolver {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");

  private IdempotencyKeyResolver() {}

  /**
   * 解析 key 模板。
   *
   * @throws IllegalArgumentException 占位符求不到值
   */
  static String resolve(String template, SdkTaskContext ctx) {
    Map<String, Object> params = ctx.parameters();
    Function<String, Object> lookup =
        field ->
            switch (field) {
              case "tenantId" -> ctx.tenantId();
              case "jobCode" -> ctx.jobCode();
              case "taskInstanceId" -> ctx.taskInstanceId();
              default -> params.get(field);
            };

    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String field = m.group(1);
      Object value = lookup.apply(field);
      if (value == null) {
        throw new IllegalArgumentException(
            "idempotent key placeholder {" + field + "} resolved to null");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
    }
    m.appendTail(out);
    return out.toString();
  }
}
