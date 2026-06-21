package com.example.batch.common.security;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-039 P1 — 凭据 envRef 运行时解析。
 *
 * <p>凭据字段值若写成 {@code ${ENV_NAME}}(ENV_NAME 匹配 {@code [A-Z][A-Z0-9_]*}),在凭据真正被用于建连 / 鉴权前由本
 * 解析器从部署侧环境变量解出真实值;解析不到则 {@link BizException} fail-fast(ResultCode {@code
 * CREDENTIAL_REF_UNRESOLVED}),<b>不静默回落空串 / 明文</b>(ADR-039 §决策一.4)。
 *
 * <p><b>P1 兼容期</b>:只对严格匹配 {@code ^\$\{[A-Z][A-Z0-9_]*\}$} 的值解析,其余值(明文、非法形态如 {@code ${lower}} /
 * {@code $NO_BRACE})一律<b>原样返回</b>——保证现有所有明文凭据配置零行为变化,不强制存量配置立刻迁移。
 *
 * <p><b>不进 P1</b>:{@code ${ENV:-default}}(P2)、{@code ${secret:provider://path}}(P3 vault)、启动期
 * sanity check、Spring {@code Environment} / application.yml {@code batch.credentials.*}
 * 二级回落(P1.1)。当前解析源仅进程 env({@link System#getenv})。
 *
 * <p><b>脱敏</b>:解析后的真实值禁入 log / trace / outbox payload(ADR-039 §决策一.5);本类只返回值,调用方负责只把它 交给执行器
 * in-memory 使用。
 */
public final class CredentialEnvResolver {

  /** {@code ${ENV_NAME}},ENV_NAME 全大写 + 下划线(POSIX env 习惯),大括号必选。 */
  private static final Pattern ENV_REF = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)\\}$");

  private CredentialEnvResolver() {}

  /** 用进程环境变量({@link System#getenv})解析。生产调用入口。 */
  public static String resolve(String raw) {
    return resolve(raw, System::getenv);
  }

  /**
   * 用给定 lookup 解析(便于单测注入)。
   *
   * @param raw 凭据原始值(可能是 envRef 或明文)
   * @param envLookup env 变量名 → 值的查找(null 表示未定义)
   * @return envRef → 解析后的真实值;非 envRef → 原样返回
   * @throws BizException envRef 形态合法但部署环境未定义该变量
   */
  static String resolve(String raw, UnaryOperator<String> envLookup) {
    if (raw == null) {
      return null;
    }
    Matcher matcher = ENV_REF.matcher(raw);
    if (!matcher.matches()) {
      // 明文或非 envRef 形态:兼容期原样放行,不破坏现有配置。
      return raw;
    }
    String name = matcher.group(1);
    String value = envLookup.apply(name);
    if (value == null || value.isEmpty()) {
      throw BizException.of(
          ResultCode.CREDENTIAL_REF_UNRESOLVED, "error.credential.env_ref_unresolved", name);
    }
    return value;
  }
}
