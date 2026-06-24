package io.github.pinpols.batch.console.domain.rbac.web.request;

import lombok.Data;

/**
 * 控制台登录请求。两种 body 形态:
 *
 * <ul>
 *   <li>明文（dev / local / CI 默认）: {@code {username, password}}
 *   <li>加密（FE 走 axios interceptor 包装）: {@code {encryptedKey, iv, ciphertext}} —— RSA-OAEP-SHA256 包装
 *       AES-256-GCM key + AES-GCM(JSON {username,password})
 * </ul>
 *
 * <p>Controller 入口检测 {@code encryptedKey} 是否存在决定走哪条解析路径。{@code @NotBlank} 移到 ConsoleLoginService
 * 入口（拿到 username/password 后再校验）,避免明文/加密两种形态共用一个 DTO 时校验冲突。
 *
 * <p>{@code batch.console.security.login-encryption.required=true} 时（prod 守护）, 缺 encryptedKey 字段直接
 * 401。
 */
@Data
public class ConsoleLoginRequest {

  // 明文字段（路径 1）
  private String username;
  private String password;

  // 加密字段（路径 2）
  private String encryptedKey;
  private String iv;
  private String ciphertext;

  /**
   * 验证码凭据(risk-based,非必填)。失败计数达阈值后登录需带此字段。<b>非机密</b>,始终走 body 顶层明文(不进加密块);加密路径下 Controller
   * 会从外层请求把它带到解密后的请求上。形态见 {@code CaptchaVerifier}(self-hosted 为 {@code challengeId:position};第三方为其
   * ticket/randstr)。
   */
  private String captchaToken;

  /** 当前 body 是否为加密形态。 */
  public boolean isEncrypted() {
    return encryptedKey != null && !encryptedKey.isBlank();
  }
}
