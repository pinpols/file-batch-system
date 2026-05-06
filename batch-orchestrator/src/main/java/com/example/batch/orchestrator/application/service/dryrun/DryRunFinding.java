package com.example.batch.orchestrator.application.service.dryrun;

import lombok.Builder;

/**
 * ADR-026 演练结果单条 finding。
 *
 * <ul>
 *   <li>{@link Severity#PASS} — 通过；
 *   <li>{@link Severity#WARN} — 校验过关但建议复核（如 bizDate 落在 holiday）；
 *   <li>{@link Severity#ERROR} — 校验失败（cron 非法 / DAG 缺 START / 必填参数缺失等）。
 * </ul>
 */
@Builder
public record DryRunFinding(
    String code, Severity severity, String scope, String message, Object detail) {

  public enum Severity {
    PASS,
    WARN,
    ERROR
  }

  public static DryRunFinding pass(String code, String scope, String message) {
    return DryRunFinding.builder()
        .code(code)
        .severity(Severity.PASS)
        .scope(scope)
        .message(message)
        .build();
  }

  public static DryRunFinding warn(String code, String scope, String message, Object detail) {
    return DryRunFinding.builder()
        .code(code)
        .severity(Severity.WARN)
        .scope(scope)
        .message(message)
        .detail(detail)
        .build();
  }

  public static DryRunFinding error(String code, String scope, String message, Object detail) {
    return DryRunFinding.builder()
        .code(code)
        .severity(Severity.ERROR)
        .scope(scope)
        .message(message)
        .detail(detail)
        .build();
  }
}
