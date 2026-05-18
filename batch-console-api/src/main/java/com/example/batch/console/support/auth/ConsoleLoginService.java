package com.example.batch.console.support.auth;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.request.auth.ConsoleLoginRequest;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import java.util.LinkedHashSet;
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

  private final ConsoleSecurityProperties securityProperties;
  private final ConsoleJwtService jwtService;
  private final ConsoleSessionRegistry sessionRegistry;
  private final ConsoleUserAccountService userAccountService;
  private final ConsolePasswordHasher passwordHasher;

  public ConsoleLoginService(
      ConsoleSecurityProperties securityProperties,
      ConsoleJwtService jwtService,
      ConsoleSessionRegistry sessionRegistry,
      ConsoleUserAccountService userAccountService,
      ConsolePasswordHasher passwordHasher) {
    this.securityProperties = securityProperties;
    this.jwtService = jwtService;
    this.sessionRegistry = sessionRegistry;
    this.userAccountService = userAccountService;
    this.passwordHasher = passwordHasher;
  }

  public ConsoleAuthTokenResponse login(ConsoleLoginRequest request) {
    Guard.require(request != null, "login request is required");
    Guard.requireText(request.getUsername(), "username is required");
    Guard.requireText(request.getPassword(), "password is required");
    // 用户名全局唯一，直接按 username 查找，租户从账号记录中获取
    ConsoleUserAccount account =
        userAccountService
            .findByUsername(request.getUsername())
            .orElseThrow(this::invalidCredentials);
    if (!account.enabled()) {
      throw invalidCredentials();
    }
    if (!passwordHasher.matches(request.getPassword(), account.passwordHash())) {
      throw invalidCredentials();
    }
    String tenantId = account.tenantId();
    long sessionVersion = sessionRegistry.nextSessionVersion(account.username(), tenantId);
    return jwtService.issueToken(
        account.username(), tenantId, new LinkedHashSet<>(account.authorities()), sessionVersion);
  }

  private BizException invalidCredentials() {
    return BizException.of(ResultCode.UNAUTHORIZED, "error.auth.invalid_credentials");
  }
}
