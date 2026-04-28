package com.example.batch.common.exception;

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

  /** 历史 literal 构造器:message 是字面量,ExceptionHandler 直接透出。 */
  public BizException(ResultCode code, String message) {
    super(message);
    this.code = code;
    this.messageKey = null;
    this.messageArgs = null;
  }

  public BizException(ResultCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.messageKey = null;
    this.messageArgs = null;
  }

  private BizException(ResultCode code, String messageKey, Object[] messageArgs, Throwable cause) {
    // super.message 用 messageKey 占位(便于 log / 异常链溯源),最终前端展示由 ExceptionHandler 翻译。
    super(messageKey, cause);
    this.code = code;
    this.messageKey = messageKey;
    this.messageArgs = messageArgs;
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
}
