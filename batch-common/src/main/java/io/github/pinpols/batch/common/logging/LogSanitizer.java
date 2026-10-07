package io.github.pinpols.batch.common.logging;

/**
 * 日志字段净化入口：清除 CR/LF 等换行符，防止用户可控值伪造日志行（log forging / CWE-117）。
 *
 * <p>调用点统一走本方法，避免同一表达式散落上百处。实现固定为
 * {@code String.valueOf(...).replaceAll("\\R", "_")}：
 *
 * <ul>
 *   <li>{@code String.valueOf} 保证 null 安全，且对非 String 值输出与 SLF4J 一致；
 *   <li>{@code replaceAll("\\R", ...)} 是 CodeQL {@code java/log-injection} 识别为有效净化的形态
 *       （见 {@code semmle/code/java/security/LogInjection.qll}：常量参 {@code \R} / {@code \n} /
 *       {@code \r} 以及不含换行的合规白名单均可被识别;黑名单写法如 {@code [\r\n]} 不被识别）。
 * </ul>
 *
 * <p>共享入口保持按字段处理与完整换行覆盖;变更正则时须同时验证运行语义和当前 CodeQL 模型,
 * 不能把扫描器识别某种写法等同于其它写法必然不安全。
 */
public final class LogSanitizer {

  private LogSanitizer() {}

  /** 净化单个日志字段；{@code null} 归一为 {@code "null"} 字面量（与 SLF4J 输出一致）。 */
  public static String value(Object raw) {
    return String.valueOf(raw).replaceAll("\\R", "_");
  }
}
