package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.persistence.BatchColumnNames;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePasswordHasher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePrincipal;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleUserAccountResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service("consoleUserAccountManagementService")
@RequiredArgsConstructor
public class ConsoleUserAccountService {

  private static final String COL_TENANT_ID = BatchColumnNames.TENANT_ID;
  private static final String COL_USERNAME = BatchColumnNames.USERNAME;

  /**
   * TENANT_ADMIN 仅可授予的角色集合。授予 ADMIN / AUDITOR / 任何未列出的角色 一律 {@link ResultCode#FORBIDDEN};升 ADMIN
   * 必须由 ADMIN 操作。
   */
  private static final Set<String> TENANT_ADMIN_GRANTABLE_ROLES =
      Set.of(ConsoleRoles.TENANT_ADMIN, ConsoleRoles.TENANT_USER, ConsoleRoles.USER);

  private final ConsoleUserAccountMapper userAccountMapper;
  private final ConsolePasswordHasher passwordHasher;
  private final ConsoleSessionRegistry sessionRegistry;

  public PageResponse<ConsoleUserAccountResponse> list(
      String tenantId, String keyword, PageRequest pageRequest) {
    String effectiveTenantId = enforceTenantScope(tenantId);
    List<Map<String, Object>> rows =
        userAccountMapper.selectByQuery(effectiveTenantId, keyword, pageRequest);
    long total = userAccountMapper.countByQuery(effectiveTenantId, keyword);
    List<ConsoleUserAccountResponse> items = rows.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  public ConsoleUserAccountResponse get(long id) {
    Map<String, Object> row = assertExists(id);
    assertSameTenantOrGlobal(str(row, COL_TENANT_ID));
    return toResponse(row);
  }

  /**
   * 创建账号。TENANT_ADMIN 调用时:
   *
   * <ul>
   *   <li>请求 body 里的 {@code tenantId} 被忽略,强制覆盖为调用者 principal.tenantId
   *   <li>{@code authoritiesCsv} 只能含 {@code ROLE_TENANT_ADMIN} / {@code ROLE_TENANT_USER};违反 → 403
   * </ul>
   *
   * ADMIN 调用不受限。
   */
  public ConsoleUserAccountResponse create(
      String tenantId,
      String username,
      String password,
      String displayName,
      String authoritiesCsv) {
    Guard.require(username != null && !username.isBlank(), "username is required");
    Guard.require(password != null && !password.isBlank(), "password is required");
    String effectiveTenantId = enforceTenantScope(tenantId);
    String normalizedAuthorities = normalizeAuthorities(authoritiesCsv);
    enforceGrantableAuthorities(normalizedAuthorities);
    if (userAccountMapper.selectByUsername(username) != null) {
      throw BizException.of(ResultCode.CONFLICT, "error.username.already_exists", username);
    }
    userAccountMapper.insert(
        effectiveTenantId,
        username,
        displayName,
        passwordHasher.encode(password),
        normalizedAuthorities,
        null);
    return toResponse(userAccountMapper.selectByUsername(username));
  }

  public ConsoleUserAccountResponse update(long id, String displayName, String authoritiesCsv) {
    Map<String, Object> row = assertExists(id);
    assertSameTenantOrGlobal(str(row, COL_TENANT_ID));
    String normalizedAuthorities = normalizeAuthorities(authoritiesCsv);
    enforceGrantableAuthorities(normalizedAuthorities);
    userAccountMapper.updateProfile(id, displayName, normalizedAuthorities);
    return toResponse(userAccountMapper.selectById(id));
  }

  /** 管理员重置他人密码:置 must_change_password=true,要求被重置者下次登录强制改密;并踢掉其现有会话。 */
  public void resetPassword(long id, String newPassword) {
    Map<String, Object> account = assertExists(id);
    assertSameTenantOrGlobal(str(account, COL_TENANT_ID));
    userAccountMapper.updatePasswordHashAndMustChange(id, passwordHasher.encode(newPassword), true);
    sessionRegistry.invalidateSession(str(account, COL_USERNAME), str(account, COL_TENANT_ID));
  }

  /**
   * 本人改密(首登强制改密的落地路径):校验旧密码,写新密码并清除 must_change_password 标志。
   *
   * <p>username 由已认证 principal 注入(controller 取 {@code authentication.principal.username}), 不接受客户端指定
   * —— 防止越权改他人密码。旧密码错误 / 新旧相同一律拒绝。
   */
  public void changeOwnPassword(String username, String currentPassword, String newPassword) {
    Guard.requireText(username, "username is required");
    Guard.require(
        currentPassword != null && !currentPassword.isBlank(), "current password is required");
    Guard.require(newPassword != null && !newPassword.isBlank(), "new password is required");
    ConsoleUserAccountEntity account =
        userAccountMapper
            .findByUsernameIgnoreCase(username)
            .orElseThrow(
                () -> BizException.of(ResultCode.NOT_FOUND, "error.account.not_found", username));
    String storedHash = account.getPasswordHash();
    if (!passwordHasher.matches(currentPassword, storedHash)) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.invalid_credentials");
    }
    if (passwordHasher.matches(newPassword, storedHash)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.password.same_as_current");
    }
    userAccountMapper.updatePasswordHashAndMustChange(
        account.getId(), passwordHasher.encode(newPassword), false);
    // 改密后踢旧会话,强制用新密码重新登录(与 reset 路径一致)。
    sessionRegistry.invalidateSession(username, account.getTenantId());
  }

  public ConsoleUserAccountResponse enable(long id) {
    Map<String, Object> row = assertExists(id);
    assertSameTenantOrGlobal(str(row, COL_TENANT_ID));
    userAccountMapper.updateEnabled(id, true);
    return toResponse(userAccountMapper.selectById(id));
  }

  public ConsoleUserAccountResponse disable(long id) {
    Map<String, Object> account = assertExists(id);
    assertSameTenantOrGlobal(str(account, COL_TENANT_ID));
    userAccountMapper.updateEnabled(id, false);
    sessionRegistry.invalidateSession(str(account, COL_USERNAME), str(account, COL_TENANT_ID));
    return toResponse(userAccountMapper.selectById(id));
  }

  private Map<String, Object> assertExists(long id) {
    return Guard.requireFound(userAccountMapper.selectById(id), "user account not found: " + id);
  }

  /**
   * 解析当前 principal。
   *
   * <p>2026-05-21 调用方审计:本 service 唯一调用方是 {@link
   * io.github.pinpols.batch.console.domain.rbac.web.ConsoleUserAccountController},类级
   * {@code @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")} 保证到 Service 时
   * principal 必已认证。 **无 @Async / 定时任务 / 内部脚本直接调用本 service**(经全仓 grep 确认)。
   *
   * <p>所以「principal == null」分支在生产路径下不可达,仅为单测(不 mock SecurityContext)留豁免;
   * 若未来加新调用方,必须同步审视新路径是否需要绕过守卫,或显式 setAuthentication 后再调。
   */
  private ConsolePrincipal currentPrincipal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal;
    }
    return null;
  }

  /** principal 含 ADMIN/AUDITOR 跨租户角色返回 true;TENANT_ADMIN/TENANT_USER/匿名返回 false。 */
  private boolean isGlobalCaller(ConsolePrincipal principal) {
    return principal != null && ConsoleRoles.hasGlobalRole(principal.authorities());
  }

  /** 非全局调用方传入的 tenantId 一律覆盖为 principal.tenantId;全局调用方保留入参。 */
  private String enforceTenantScope(String requestedTenantId) {
    ConsolePrincipal principal = currentPrincipal();
    if (principal == null) return requestedTenantId; // 无 principal 上下文,豁免
    if (isGlobalCaller(principal)) return requestedTenantId;
    return principal.tenantId();
  }

  /** 操作目标账号 tenantId 与 principal 不同 → 403,除非 principal 是全局角色。 */
  private void assertSameTenantOrGlobal(String targetTenantId) {
    ConsolePrincipal principal = currentPrincipal();
    if (principal == null) return;
    if (isGlobalCaller(principal)) return;
    if (targetTenantId == null || !targetTenantId.equals(principal.tenantId())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.account.cross_tenant_denied");
    }
  }

  /** TENANT_ADMIN 不可授予 ADMIN/AUDITOR;ADMIN 不受限。 */
  private void enforceGrantableAuthorities(String authoritiesCsv) {
    ConsolePrincipal principal = currentPrincipal();
    if (principal == null) return;
    if (isGlobalCaller(principal)) return;
    Set<String> requested =
        Arrays.stream(authoritiesCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    for (String authority : requested) {
      if (!TENANT_ADMIN_GRANTABLE_ROLES.contains(authority)) {
        throw BizException.of(ResultCode.FORBIDDEN, "error.account.role_grant_denied", authority);
      }
    }
  }

  private ConsoleUserAccountResponse toResponse(Map<String, Object> row) {
    return new ConsoleUserAccountResponse(
        row.get("id") instanceof Number n ? n.longValue() : null,
        str(row, COL_TENANT_ID),
        str(row, COL_USERNAME),
        str(row, "display_name"),
        str(row, "authorities_csv"),
        Boolean.TRUE.equals(row.get("enabled")),
        str(row, "created_at"),
        str(row, "updated_at"));
  }

  private String normalizeAuthorities(String raw) {
    if (raw == null || raw.isBlank()) {
      return ConsoleRoles.TENANT_USER;
    }
    return raw.trim().toUpperCase();
  }

  private static String str(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? null : String.valueOf(v);
  }
}
