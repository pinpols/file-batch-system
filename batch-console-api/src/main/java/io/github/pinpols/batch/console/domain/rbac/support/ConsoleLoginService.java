package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.rbac.web.request.ConsoleLoginRequest;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 控制台登录服务：按用户名全局查找账号、校验密码并签发 JWT。
 *
 * <p>关键安全点：
 *
 * <ul>
 *   <li><b>用户名全局唯一</b>：tenantId 从 {@code ConsoleUserAccount} 记录读取，<b>不接受</b>客户端请求里指定 ——防止已知账号名 +
 *       任意租户猜测越权。
 *   <li><b>统一错误消息</b>：账号不存在 / 账号禁用 / 密码错误都抛同一个 {@code invalidCredentials} （{@code UNAUTHORIZED} +
 *       "invalid username or password"），不向客户端泄露"用户是否存在"—— 防用户枚举攻击。
 *   <li><b>会话版本递增</b>：登录成功立即 {@code nextSessionVersion}——单点登录开启时自动踢旧会话 （见 {@link
 *       ConsoleSessionRegistry}）。
 * </ul>
 */
@Service
public class ConsoleLoginService {

  private final ConsoleJwtService jwtService;
  private final ConsoleSessionRegistry sessionRegistry;
  private final ConsoleUserAccountServiceSupport userAccountService;
  private final ConsolePasswordHasher passwordHasher;
  private final LoginProtectionService loginProtectionService;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  public ConsoleLoginService(
      ConsoleJwtService jwtService,
      ConsoleSessionRegistry sessionRegistry,
      ConsoleUserAccountServiceSupport userAccountService,
      ConsolePasswordHasher passwordHasher,
      LoginProtectionService loginProtectionService,
      ConsoleRequestMetadataResolver requestMetadataResolver) {
    this.jwtService = jwtService;
    this.sessionRegistry = sessionRegistry;
    this.userAccountService = userAccountService;
    this.passwordHasher = passwordHasher;
    this.loginProtectionService = loginProtectionService;
    this.requestMetadataResolver = requestMetadataResolver;
  }

  public ConsoleAuthTokenResponse login(ConsoleLoginRequest request) {
    Guard.require(request != null, "login request is required");
    Guard.requireText(request.getUsername(), "username is required");
    Guard.requireText(request.getPassword(), "password is required");
    String username = request.getUsername();
    String clientIp = resolveClientIp();
    // 风控第一关:失败计数达阈值则要求验证码(不通过抛 CAPTCHA_REQUIRED);总开关关时静默放行。
    loginProtectionService.assertCaptchaSatisfied(username, clientIp, request.getCaptchaToken());
    // 用户名全局唯一，直接按 username 查找，租户从账号记录中获取
    Optional<ConsoleUserAccount> found = userAccountService.findByUsername(username);
    boolean credentialsValid =
        found.isPresent()
            && found.get().enabled()
            && passwordHasher.matches(request.getPassword(), found.get().passwordHash());
    if (!credentialsValid) {
      // 记失败 + 渐进退避(总开关关时 no-op),再抛统一的 invalid credentials(防用户枚举)。
      loginProtectionService.onLoginFailure(username, clientIp);
      throw invalidCredentials();
    }
    ConsoleUserAccount account = found.get();
    loginProtectionService.onLoginSuccess(username);
    String tenantId = account.tenantId();
    long sessionVersion = sessionRegistry.nextSessionVersion(account.username(), tenantId);
    // 登录响应带 mustChangePassword 标记:FE 据此跳转改密页;敏感操作拦截见
    // ConsoleMustChangePasswordGuard(改密前仅放行 auth/改密端点)。
    return jwtService
        .issueToken(
            account.username(),
            tenantId,
            new LinkedHashSet<>(account.authorities()),
            sessionVersion)
        .withMustChangePassword(account.mustChangePassword());
  }

  /** 从 request metadata 取客户端 IP(已按 trust-forwarded-headers 解析);非 Servlet 上下文回退空串。 */
  private String resolveClientIp() {
    var metadata = requestMetadataResolver.current();
    String ip = metadata == null ? null : metadata.clientIp();
    return ip == null ? "" : ip;
  }

  private BizException invalidCredentials() {
    return BizException.of(ResultCode.UNAUTHORIZED, "error.auth.invalid_credentials");
  }
}
