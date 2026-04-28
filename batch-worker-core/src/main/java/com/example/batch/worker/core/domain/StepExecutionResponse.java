package com.example.batch.worker.core.domain;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.i18n.BizExceptionUtils;
import com.example.batch.common.i18n.LocalizedError;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pipeline 单步执行的返回结果。统一封装成功标志、业务状态码与描述信息;并携带 i18n 三元组 ({@code errorKey} + {@code errorArgs} JSON)
 * 用于跨进程透传到 orchestrator 持久化。
 *
 * <p>典型用法:
 *
 * <ul>
 *   <li>成功: {@link #successResponse()}
 *   <li>失败 + i18n: {@link #failure(BizException, ObjectMapper)} — 自动从 {@link
 *       BizException#getMessageKey()} / {@code messageArgs()} 抽 i18n 信息
 *   <li>失败 + 旧 literal: {@code new StepExecutionResponse(false, code, message, null, null)}
 * </ul>
 */
public record StepExecutionResponse(
    boolean success, String code, String message, String errorKey, String errorArgs) {

  public StepExecutionResponse(boolean success, String code, String message) {
    this(success, code, message, null, null);
  }

  public static StepExecutionResponse successResponse() {
    return new StepExecutionResponse(true, "SUCCESS", "ok", null, null);
  }

  /**
   * 从 {@link BizException} 构造失败结果,自动抽取 i18n key/args(用于跨进程在 orchestrator 持久化时按 Locale 重渲染)。
   *
   * @param ex 业务异常 — 必须是 {@link BizException}(才能拿到 messageKey + args);非 BizException 请用普通构造器
   * @param objectMapper 用于把 args 数组序列化为 JSON 字符串
   */
  public static StepExecutionResponse failure(BizException ex, ObjectMapper objectMapper) {
    LocalizedError localized = BizExceptionUtils.toLocalizedError(ex, null, objectMapper);
    return new StepExecutionResponse(
        false,
        ex.getCode().name(),
        localized.renderedMessage(),
        localized.key(),
        localized.argsJson());
  }
}
