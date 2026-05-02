package com.example.batch.console.service;

import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.support.ConsoleMenuRegistry;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.auth.ConsoleJwtService;
import com.example.batch.console.support.auth.ConsoleLoginService;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.support.auth.ConsoleRoles;
import com.example.batch.console.support.auth.ConsoleSessionRegistry;
import com.example.batch.console.web.request.auth.ConsoleLoginRequest;
import com.example.batch.console.web.response.auth.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * Console 认证应用层：登录 / 签发 token / 返回当前用户画像（含菜单）的 facade。
 *
 * <p>关键语义：
 *
 * <ul>
 *   <li><b>每次 issueToken 都递增 session version</b>（{@link ConsoleSessionRegistry#nextSessionVersion}）
 *       —— 新登录自动踢旧会话，同一账号无法多端并存（{@code singleSessionEnabled=true} 时生效）。
 *   <li><b>tenantId 解析顺序</b>：Principal.tenantId → request metadata → {@code defaultTenantId} 兜底。
 *       前两个缺失时用默认租户而不是拒绝，兼容无 Principal 的边界场景（如 legacy header-only）。
 *   <li><b>authorities 兜底</b>：principal 无 authorities 或只含泛化的 {@link ConsoleRoles#USER} 时， 用服务端
 *       {@code defaultAuthorities} 替换——防止未正确配置角色的账号获得空权限集。
 *   <li><b>profile 带菜单</b>：{@link ConsoleAuthProfileResponse} 除身份字段外还附带 {@link
 *       ConsoleMenuRegistry#filterByAuthorities} 过滤后的菜单树，前端不需再单独拉菜单接口。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsoleAuthApplicationService {

  private final ConsoleJwtService jwtService;
  private final ConsoleLoginService loginService;
  private final ConsoleSessionRegistry sessionRegistry;
  private final ConsoleSecurityProperties securityProperties;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  public ConsoleAuthTokenResponse login(ConsoleLoginRequest request) {
    return loginService.login(request);
  }

  public ConsoleAuthTokenResponse issueToken(Authentication authentication) {
    String username = username(authentication);
    String tenantId = tenantId(authentication);
    long sessionVersion = sessionRegistry.nextSessionVersion(username, tenantId);
    return jwtService.issueToken(username, tenantId, authorities(authentication), sessionVersion);
  }

  public ConsoleAuthProfileResponse profile(Authentication authentication) {
    Set<String> auths = authorities(authentication);
    return new ConsoleAuthProfileResponse(
        username(authentication),
        tenantId(authentication),
        auths,
        ConsoleMenuRegistry.filterByAuthorities(auths));
  }

  private String username(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.username();
    }
    if (authentication != null
        && authentication.getPrincipal() instanceof UserDetails userDetails) {
      return userDetails.getUsername();
    }
    return authentication == null ? null : authentication.getName();
  }

  private String tenantId(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.tenantId();
    }
    // N-3：requestMetadataResolver.current() 在非 Servlet 上下文（@Async / 后台任务）可能
    // 返回全 null 字段的 mock metadata；对 null 的链式调用会 NPE。这里整段防御。
    var metadata = requestMetadataResolver.current();
    String resolved = metadata == null ? null : metadata.tenantId();
    return resolved == null || resolved.isBlank()
        ? securityProperties.getDefaultTenantId()
        : resolved;
  }

  private Set<String> authorities(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.authorities();
    }
    Set<String> resolved = new LinkedHashSet<>();
    if (authentication != null) {
      for (GrantedAuthority grantedAuthority : authentication.getAuthorities()) {
        resolved.add(grantedAuthority.getAuthority());
      }
    }
    if (resolved.isEmpty() || resolved.contains(ConsoleRoles.USER)) {
      resolved.clear();
      resolved.addAll(securityProperties.getDefaultAuthorities());
    }
    return resolved;
  }
}
