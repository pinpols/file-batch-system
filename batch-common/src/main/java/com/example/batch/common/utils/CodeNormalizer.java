package com.example.batch.common.utils;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 配置码（configuration code）归一与格式校验工具。
 *
 * <p>批量调度平台里有大量"软字符串"字段在多张表之间引用（{@code worker_group} /
 * {@code window_code} / {@code calendar_code} / {@code queue_code} 等），历史上因为既没统一大小写、
 * 也没拦字典外的值，导致同一个概念在 job_definition / worker_registry / 运行时实例之间出现
 * {@code IMPORT / import / Import} 并存、{@code always_open / always-open} 混用的脏数据， 运行时
 * worker 匹配 SQL 等值比较直接失配，任务永远卡在 WAITING。
 *
 * <p><b>归一规则</b>（两类）：
 *
 * <ul>
 *   <li>{@link #normalizeGroupCode} — 分组码（worker_group / tenant_id 等）：去空白 → 大写 →
 *       格式检查 {@code ^[A-Z][A-Z0-9_]*$}。入口统一为大写是行业惯例（如 Kubernetes Labels / Kafka Topic
 *       的 PascalCase 变体），匹配规则明确。
 *   <li>{@link #normalizeConfigCode} — 配置码（window_code / calendar_code / queue_code /
 *       channel_code / template_code 等）：去空白 → 小写 → 连字符替为下划线 → 格式检查 {@code
 *       ^[a-z0-9][a-z0-9_]*$}。配置码是运营用户手输的可读字符串，小写+下划线对 URL
 *       / 日志 / SQL identifier 都友好。
 * </ul>
 *
 * <p><b>空值约定</b>：入参为 null / 空白 一律返回 null，不抛异常——空值语义（"不指定"）由业务层决定是否合法。
 *
 * <p><b>异常路径</b>：归一后格式不匹配即抛 {@link BizException}
 * ({@code ResultCode.INVALID_ARGUMENT})，错误消息包含原始值以便排查。
 */
public final class CodeNormalizer {

  private static final Pattern GROUP_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
  private static final Pattern CONFIG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_]*$");

  private CodeNormalizer() {}

  /** 分组码归一（大写）：worker_group / tenant_id 等。 */
  public static String normalizeGroupCode(String raw, String fieldName) {
    String trimmed = trimToNull(raw);
    if (trimmed == null) {
      return null;
    }
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (!GROUP_PATTERN.matcher(upper).matches()) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "invalid "
              + fieldName
              + " '"
              + raw
              + "': must match "
              + GROUP_PATTERN.pattern()
              + " (after uppercasing)");
    }
    return upper;
  }

  /** 配置码归一（小写 + 下划线）：window_code / calendar_code / queue_code / channel_code 等。 */
  public static String normalizeConfigCode(String raw, String fieldName) {
    String trimmed = trimToNull(raw);
    if (trimmed == null) {
      return null;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT).replace('-', '_');
    if (!CONFIG_PATTERN.matcher(lower).matches()) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "invalid "
              + fieldName
              + " '"
              + raw
              + "': must match "
              + CONFIG_PATTERN.pattern()
              + " (after lowercasing and replacing '-' with '_')");
    }
    return lower;
  }

  /** 宽松归一：只 trim + upper/lower，不做格式校验（供 Excel 预览阶段用，真正 apply 时再强校验）。 */
  public static String toUpperOrNull(String raw) {
    String trimmed = trimToNull(raw);
    return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  public static String toConfigFormOrNull(String raw) {
    String trimmed = trimToNull(raw);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT).replace('-', '_');
  }

  private static String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
