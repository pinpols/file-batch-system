package com.example.batch.common.i18n;

/**
 * 持久化层的 i18n-aware 错误三元组。配合 {@code error_key VARCHAR + error_args JSONB + error_message} 三列存储:
 *
 * <ul>
 *   <li>{@link #key()} 为 i18n message key(如 {@code error.task.lease_renew_rejected});非 null 时表示错误源自
 *       {@link com.example.batch.common.exception.BizException#of}, 历史日志按用户当前 Locale 重渲染。
 *   <li>{@link #argsJson()} 为占位符参数 JSON 数组(如 {@code ["acme","42"]}),按 {@code MessageFormat} 的
 *       {@code {0}/{1}/...} 顺序填充。
 *   <li>{@link #renderedMessage()} 为写入时已翻译的字符串,作为:
 *       <ul>
 *         <li>无 i18n 时唯一展示来源(老 literal 异常 / 第三方异常)
 *         <li>有 i18n 时的 fallback,key 在新版本中被删/改时仍可读
 *         <li>运维直接 SELECT 表时不需要再查 messages.properties
 *       </ul>
 * </ul>
 *
 * <p>通过 {@link BizExceptionUtils#toLocalizedError(Throwable, BizMessageResolver)} 从异常构造, 通过 {@link
 * LocalizedErrorRenderer#render(LocalizedError, java.util.Locale)} 在读路径按当前 Locale 输出文案。
 */
public record LocalizedError(String key, String argsJson, String renderedMessage) {

  public static final LocalizedError EMPTY = new LocalizedError(null, null, null);

  public boolean hasKey() {
    return key != null && !key.isBlank();
  }

  public boolean hasMessage() {
    return renderedMessage != null && !renderedMessage.isBlank();
  }
}
