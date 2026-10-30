package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleUserAccountResponse;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.param.ConsoleUserAccountInsertParam;
import io.github.pinpols.batch.console.domain.rbac.param.ConsoleUserAccountInsertProvisionedParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePasswordHasher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import io.github.pinpols.batch.console.domain.rbac.view.ConsoleUserAccountRow;
import io.github.pinpols.batch.console.domain.rbac.view.TenantRow;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service("consoleUserAccountManagementService")
@RequiredArgsConstructor
public class ConsoleUserAccountService {

  private static final String USERNAME_REQUIRED = "username is required";

  /**
   * TENANT_ADMIN 仅可授予的角色集合。授予 ADMIN / AUDITOR / 任何未列出的角色 一律 {@link ResultCode#FORBIDDEN};升 ADMIN
   * 必须由 ADMIN 操作。
   */
  private static final Set<String> TENANT_ADMIN_GRANTABLE_ROLES =
      Set.of(ConsoleRoles.TENANT_ADMIN, ConsoleRoles.TENANT_USER);

  private final ConsoleUserAccountMapper userAccountMapper;
  private final TenantMapper tenantMapper;
  private final ConsolePasswordHasher passwordHasher;
  private final ConsoleSessionRegistry sessionRegistry;

  public PageResponse<ConsoleUserAccountResponse> list(
      String tenantId, String keyword, Boolean enabled, PageRequest pageRequest) {
    String effectiveTenantId = enforceTenantScope(tenantId);
    List<ConsoleUserAccountRow> rows =
        userAccountMapper.selectByQuery(effectiveTenantId, keyword, enabled, pageRequest);
    long total = userAccountMapper.countByQuery(effectiveTenantId, keyword, enabled);
    List<ConsoleUserAccountResponse> items = rows.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  public ConsoleUserAccountResponse get(long id) {
    ConsoleUserAccountRow row = assertExists(id);
    assertSameTenantOrGlobal(row.tenantId());
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
    Guard.require(Texts.hasText(username), USERNAME_REQUIRED);
    Guard.require(password != null && !password.isBlank(), "password is required");
    String effectiveTenantId = enforceTenantScope(tenantId);
    String normalizedAuthorities = normalizeAuthorities(authoritiesCsv);
    enforceGrantableAuthorities(normalizedAuthorities);
    enforceRoleTenantBinding(effectiveTenantId, normalizedAuthorities);
    ConsoleUserAccountRow existingAccount = userAccountMapper.selectByUsername(username);
    if (EmptyChecks.isNotNull(existingAccount)) {
      throw BizException.of(ResultCode.CONFLICT, "error.username.already_exists", username);
    }
    ConsoleUserAccountInsertParam consoleUserAccountInsertParam =
        ConsoleUserAccountInsertParam.builder()
            .tenantId(effectiveTenantId)
            .username(username)
            .displayName(displayName)
            .passwordHash(passwordHasher.encode(password))
            .authoritiesCsv(normalizedAuthorities)
            .createdBy(null)
            .build();
    userAccountMapper.insert(consoleUserAccountInsertParam);
    ConsoleUserAccountRow createdAccount = userAccountMapper.selectByUsername(username);
    return toResponse(createdAccount);
  }

  /** 批量开户沿用单账号的租户与角色守卫，初始密码只存哈希并标记改密提醒。 */
  public ConsoleUserAccountResponse createProvisioned(
      String tenantId, String username, String password, String displayName, String role) {
    Guard.require(Texts.hasText(username), USERNAME_REQUIRED);
    String effectiveTenantId = enforceTenantScope(tenantId);
    String normalizedRole = normalizeAuthorities(role);
    enforceGrantableAuthorities(normalizedRole);
    enforceRoleTenantBinding(effectiveTenantId, normalizedRole);
    ConsoleUserAccountRow existingAccount = userAccountMapper.selectByUsername(username);
    if (EmptyChecks.isNotNull(existingAccount)) {
      throw BizException.of(ResultCode.CONFLICT, "error.username.already_exists", username);
    }
    ConsoleUserAccountInsertProvisionedParam consoleUserAccountInsertProvisionedParam =
        ConsoleUserAccountInsertProvisionedParam.builder()
            .tenantId(effectiveTenantId)
            .username(username)
            .displayName(displayName)
            .passwordHash(passwordHasher.encode(password))
            .authoritiesCsv(normalizedRole)
            .build();
    userAccountMapper.insertProvisioned(consoleUserAccountInsertProvisionedParam);
    ConsoleUserAccountRow createdAccount = userAccountMapper.selectByUsername(username);
    return toResponse(createdAccount);
  }

  public ConsoleUserAccountResponse update(long id, String displayName, String authoritiesCsv) {
    ConsoleUserAccountRow row = assertExists(id);
    assertSameTenantOrGlobal(row.tenantId());
    String normalizedAuthorities = normalizeAuthorities(authoritiesCsv);
    enforceGrantableAuthorities(normalizedAuthorities);
    userAccountMapper.updateProfile(id, displayName, normalizedAuthorities);
    ConsoleUserAccountRow updatedAccount = userAccountMapper.selectById(id);
    return toResponse(updatedAccount);
  }

  /** 管理员重置他人密码:置 must_change_password=true,提醒被重置者尽快改密;并踢掉其现有会话。 */
  public void resetPassword(long id, String newPassword) {
    ConsoleUserAccountRow account = assertExists(id);
    assertSameTenantOrGlobal(account.tenantId());
    userAccountMapper.updatePasswordHashAndMustChange(id, passwordHasher.encode(newPassword), true);
    sessionRegistry.invalidateSession(account.username(), account.tenantId());
  }

  /**
   * 本人改密:校验旧密码,写新密码并清除 must_change_password 提示标志。
   *
   * <p>username 由已认证 principal 注入(controller 取 {@code authentication.principal.username}), 不接受客户端指定
   * —— 防止越权改他人密码。旧密码错误 / 新旧相同一律拒绝。
   */
  public void changeOwnPassword(String username, String currentPassword, String newPassword) {
    Guard.requireText(username, USERNAME_REQUIRED);
    Guard.require(
        currentPassword != null && !currentPassword.isBlank(), "current password is required");
    Guard.require(newPassword != null && !newPassword.isBlank(), "new password is required");
    ConsoleUserAccountEntity account = userAccountMapper
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
    ConsoleUserAccountRow row = assertExists(id);
    assertSameTenantOrGlobal(row.tenantId());
    userAccountMapper.updateEnabled(id, true);
    ConsoleUserAccountRow enabledAccount = userAccountMapper.selectById(id);
    return toResponse(enabledAccount);
  }

  public ConsoleUserAccountResponse disable(long id) {
    ConsoleUserAccountRow account = assertExists(id);
    assertSameTenantOrGlobal(account.tenantId());
    userAccountMapper.updateEnabled(id, false);
    sessionRegistry.invalidateSession(account.username(), account.tenantId());
    ConsoleUserAccountRow disabledAccount = userAccountMapper.selectById(id);
    return toResponse(disabledAccount);
  }

  private ConsoleUserAccountRow assertExists(long id) {
    ConsoleUserAccountRow account = userAccountMapper.selectById(id);
    return Guard.requireFound(account, "user account not found: " + id);
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

  /** 所有账号只能使用四个正式角色；TENANT_ADMIN 不可授予 ADMIN/AUDITOR。 */
  private void enforceGrantableAuthorities(String authoritiesCsv) {
    Set<String> requested = parseAuthorities(authoritiesCsv);
    for (String authority : requested) {
      if (!ConsoleRoles.ALL.contains(authority)) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.account.invalid_role", authority);
      }
    }
    ConsolePrincipal principal = currentPrincipal();
    if (EmptyChecks.isNull(principal) || isGlobalCaller(principal)) return;
    for (String authority : requested) {
      if (!TENANT_ADMIN_GRANTABLE_ROLES.contains(authority)) {
        throw BizException.of(ResultCode.FORBIDDEN, "error.account.role_grant_denied", authority);
      }
    }
  }

  /**
   * 平台角色只能绑定 system 租户,租户角色必须绑定真实 ACTIVE 业务租户。
   *
   * <p>批量开户预览也有同口径校验,这里作为最终落库前守卫,避免预览后租户状态变化或单账号路径漏校验。
   */
  private void enforceRoleTenantBinding(String tenantId, String authoritiesCsv) {
    Set<String> requested = parseAuthorities(authoritiesCsv);
    boolean hasPlatformRole = requested.stream()
        .anyMatch(role -> Set.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR).contains(role));
    boolean hasTenantRole = requested.stream()
        .anyMatch(
            role -> Set.of(ConsoleRoles.TENANT_ADMIN, ConsoleRoles.TENANT_USER).contains(role));
    if (hasPlatformRole && hasTenantRole) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.account.mixed_role_scope");
    }
    if (hasPlatformRole) {
      if (!"system".equals(tenantId)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.account.platform_role_requires_system_tenant");
      }
      return;
    }
    if (!hasTenantRole) return;
    if (!Texts.hasText(tenantId)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.account.tenant_not_active");
    }
    TenantRow tenant = tenantMapper.selectByTenantId(tenantId);
    if (EmptyChecks.isNull(tenant) || !"ACTIVE".equals(tenant.status())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.account.tenant_not_active", tenantId);
    }
  }

  private Set<String> parseAuthorities(String authoritiesCsv) {
    return Arrays.stream(authoritiesCsv.split(","))
        .map(String::trim)
        .filter(EmptyChecks::isNotEmpty)
        .collect(Collectors.toSet());
  }

  private ConsoleUserAccountResponse toResponse(ConsoleUserAccountRow row) {
    boolean enabled = Boolean.TRUE.equals(row.enabled());
    String createdAtText =
        EmptyChecks.isNull(row.createdAt()) ? null : String.valueOf(row.createdAt());
    String updatedAtText =
        EmptyChecks.isNull(row.updatedAt()) ? null : String.valueOf(row.updatedAt());
    return new ConsoleUserAccountResponse(
        row.id(),
        row.tenantId(),
        row.username(),
        row.displayName(),
        row.authoritiesCsv(),
        enabled,
        createdAtText,
        updatedAtText);
  }

  private String normalizeAuthorities(String raw) {
    if (raw == null || raw.isBlank()) {
      return ConsoleRoles.TENANT_USER;
    }
    String trimmedAuthorities = raw.trim();
    String normalizedAuthorities = trimmedAuthorities.toUpperCase();
    return normalizedAuthorities;
  }
}
