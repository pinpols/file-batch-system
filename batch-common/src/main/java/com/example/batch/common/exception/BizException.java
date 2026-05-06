package com.example.batch.common.exception;

import com.example.batch.common.enums.FailureClass;
import com.example.batch.common.enums.ResultCode;

/**
 * 业务异常。两种构造形式:
 *
 * <ol>
 *   <li><b>新规范(i18n)</b>:{@link #of(ResultCode, String, Object...)} 用 i18n key + 占位符 args
 *       throw,ExceptionHandler 链路按 Accept-Language 翻译成对应语种文案返回前端。
 *   <li><b>历史 literal</b>:{@link #BizException(ResultCode, String)} 直接传字面量字符串。ExceptionHandler 检测 到
 *       messageKey 为 null 则原样透出。新代码不要再用,逐步迁移到 i18n。
 * </ol>
 */
public class BizException extends RuntimeException {

  private final ResultCode code;
  private final String messageKey;
  private final transient Object[] messageArgs;

  /** ADR-012 失败分类。null 时由 orchestrator 端 FailureClassifier 兜底；显式声明优先级最高。 */
  private final FailureClass failureClass;

  /** 历史 literal 构造器:message 是字面量,ExceptionHandler 直接透出。 */
  public BizException(ResultCode code, String message) {
    super(message);
    this.code = code;
    this.messageKey = null;
    this.messageArgs = null;
    this.failureClass = null;
  }

  public BizException(ResultCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.messageKey = null;
    this.messageArgs = null;
    this.failureClass = null;
  }

  private BizException(ResultCode code, String messageKey, Object[] messageArgs, Throwable cause) {
    this(code, messageKey, messageArgs, cause, null);
  }

  private BizException(
      ResultCode code,
      String messageKey,
      Object[] messageArgs,
      Throwable cause,
      FailureClass failureClass) {
    // super.message 用 messageKey 占位(便于 log / 异常链溯源),最终前端展示由 ExceptionHandler 翻译。
    super(messageKey, cause);
    this.code = code;
    this.messageKey = messageKey;
    this.messageArgs = messageArgs;
    this.failureClass = failureClass;
  }

  /** 新规范:i18n key + args。messageKey 形如 {@code error.tenant.already_exists},占位符用 {0}/{1}/...。 */
  public static BizException of(ResultCode code, String messageKey, Object... args) {
    return new BizException(code, messageKey, args, null);
  }

  /** 新规范带 cause 版本。 */
  public static BizException of(
      ResultCode code, String messageKey, Throwable cause, Object... args) {
    return new BizException(code, messageKey, args, cause);
  }

  /** 新规范 + ADR-012 显式失败分类（业务方明确根因时用）。 */
  public static BizException of(
      ResultCode code, FailureClass failureClass, String messageKey, Object... args) {
    return new BizException(code, messageKey, args, null, failureClass);
  }

  /** 新规范 + ADR-012 显式失败分类 + cause。 */
  public static BizException of(
      ResultCode code,
      FailureClass failureClass,
      String messageKey,
      Throwable cause,
      Object... args) {
    return new BizException(code, messageKey, args, cause, failureClass);
  }

  public ResultCode getCode() {
    return code;
  }

  /** i18n message key;null 表示走历史 literal 路径(message 已是字面量)。 */
  public String getMessageKey() {
    return messageKey;
  }

  /** i18n 占位符参数;message key 为 null 时也为 null。 */
  public Object[] getMessageArgs() {
    return messageArgs;
  }

  /** ADR-012 显式失败分类;null = 由 FailureClassifier 兜底推断。 */
  public FailureClass getFailureClass() {
    return failureClass;
  }
}
