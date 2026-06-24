package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制台登录防暴力破解 / 撞库配置（IP 限流之外的「账号维度失败退避 + risk-based 验证码」两层）。
 *
 * <p>设计见 {@code docs/design/console-login-bruteforce-protection.md}。关键安全约束:达阈值<b>不锁账号</b>(规避
 * account-lockout DoS),而是升级到「该次登录要求验证码」。
 *
 * <p><b>总开关默认 off</b>:opt-in,不开则登录流程零变化(对现有部署零影响)。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.login-protection")
public class LoginProtectionProperties {

  /** 总开关,默认关。关闭时 {@code LoginProtectionService} 完全旁路(不计数、不退避、不要求验证码)。 */
  private boolean enabled = false;

  /** 同账号或同 IP 在滑动窗口内失败达到该次数 → 触发验证码。默认 5。 */
  private int failThreshold = 5;

  /** 失败计数滑动窗口(分钟)。默认 15。 */
  private int failWindowMinutes = 15;

  /** 渐进退避步长(毫秒):失败响应人为延迟 = backoffStepMillis × 当前失败数。默认 200。 */
  private long backoffStepMillis = 200L;

  /** 退避封顶(毫秒)。默认 2000(2s),避免拖死线程池。 */
  private long backoffCapMillis = 2000L;
}
