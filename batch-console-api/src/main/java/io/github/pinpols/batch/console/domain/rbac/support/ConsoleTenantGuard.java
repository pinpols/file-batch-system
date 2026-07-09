package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 租户访问守卫：所有需要落租户维度的 console 操作经此解析/校验目标租户，返回归一化的 tenantId。
 *
 * <p>两条路径：
 *
 * <ul>
 *   <li><b>全局角色</b>（{@code ADMIN / AUDITOR / CONFIG_ADMIN}，见 {@link ConsoleRoles#hasGlobalRole}）：
 *       跨租户操作必须<b>显式</b>传 {@code requestTenantId}；为空直接 {@code INVALID_ARGUMENT} 拒绝
 *       ——防止全局角色因遗漏参数"默认当前租户"或"全量生效"造成意外越界。
 *   <li><b>租户角色</b>：以 JWT 里的 {@code tenantId} 为准，{@code requestTenantId} 非空时必须匹配， 不匹配直接 {@code
 *       FORBIDDEN}——跨租户访问一律拒绝，即使是只读请求。
 * </ul>
 *
 * <p>Session 未激活（例如异步上下文）时 {@code ConsoleRequestMetadata} 读取会静默降级为 null， 由上游回退或抛 {@code
 * UNAUTHORIZED}。
 */
@Component
@RequiredArgsConstructor
public class ConsoleTenantGuard {

  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  /** S-1.3 加固：只允许字母 / 数字 / 下划线 / 连字符；防止 requestTenantId 带路径字符（如 {@code ../x}）或特殊字符绕过后续 DB 查询语义。 */
  private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

  public String resolveTenant(String requestTenantId) {
    String normalized = sanitizeTenantId(requestTenantId);
    // 全局角色（ADMIN / AUDITOR / CONFIG_ADMIN）：必须显式指定目标租户
    if (isCurrentUserGlobal()) {
      if (normalized == null) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.tenant.required");
      }
      return normalized;
    }

    // 租户角色 / 系统上下文：以 JWT 为权威；JWT / RequestScope 均缺失时允许 requestTenantId
    // fallback 以支持 @Async / 定时任务等系统路径。requestTenantId 与 JWT tenantId
    // 不一致时抛 FORBIDDEN —— 这是越权的真正拦截点（参见 shouldRejectMismatchedTenant 测试）
    ConsoleRequestMetadata metadata = currentMetadataOrNull();
    String authenticatedTenantId = authenticatedTenantId();
    String effectiveTenantId =
        authenticatedTenantId != null
            ? authenticatedTenantId
            : metadata != null ? metadata.tenantId() : null;
    if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
      effectiveTenantId = normalized;
    }
    if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
      // 认证已通过(JWT 解析成功)但租户上下文判定失败(JWT 缺 tenant claim / 损坏 / RequestScope 缺失且无 fallback)
      // → 严格语义是 FORBIDDEN(授权失败),而非 UNAUTHORIZED(认证失败)
      throw BizException.of(ResultCode.FORBIDDEN, "error.tenant.context_missing");
    }
    if (normalized != null && !normalized.equals(effectiveTenantId)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch");
    }
    return effectiveTenantId;
  }

  /** 返回经过 trim + 格式校验的 tenantId；null / 空 / 全空白 → null；非法字符 → INVALID_ARGUMENT。 */
  private String sanitizeTenantId(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (!TENANT_ID_PATTERN.matcher(trimmed).matches()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.tenant.required");
    }
    return trimmed;
  }

  public void assertTenantAllowed(String requestedTenantId) {
    resolveTenant(requestedTenantId);
  }

  /**
   * 返回当前调用方的租户过滤作用域,用于<b>无显式 tenantId 入参</b>的列表 / 只读枚举端点 （例如 {@code listTenants} / {@code
   * triggerList}):
   *
   * <ul>
   *   <li><b>全局角色</b>（ADMIN / AUDITOR / CONFIG_ADMIN）:返回 {@code null}，语义为「不设租户过滤,可见全部」。
   *   <li><b>租户角色</b>:返回 JWT / RequestScope 里的 tenantId,调用方据此把结果收敛到自身租户; 租户上下文缺失 → {@code
   *       FORBIDDEN}(与 {@link #resolveTenant} 一致,不静默放行全量)。
   * </ul>
   *
   * <p>注意:与 {@link #resolveTenant} 不同,本方法对全局角色<b>不</b>强制显式租户参数(列表端点本就无该参数), 而是返回 null
   * 让调用方放行全量;跨租户越权拦截由租户角色分支的 tenantId 收敛完成。
   */
  public String currentTenantScopeOrNull() {
    if (isCurrentUserGlobal()) {
      return null;
    }
    String authenticatedTenantId = authenticatedTenantId();
    ConsoleRequestMetadata metadata = currentMetadataOrNull();
    String effectiveTenantId =
        authenticatedTenantId != null
            ? authenticatedTenantId
            : metadata != null ? metadata.tenantId() : null;
    if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.tenant.context_missing");
    }
    return effectiveTenantId;
  }

  private ConsoleRequestMetadata currentMetadataOrNull() {
    try {
      return requestMetadataResolver.current();
    } catch (IllegalStateException exception) {
      SwallowedExceptionLogger.info(
          ConsoleTenantGuard.class, "catch:IllegalStateException", exception);

      return null;
    } catch (ScopeNotActiveException exception) {
      return null;
    }
  }

  private boolean isCurrentUserGlobal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return ConsoleRoles.hasGlobalRole(principal.authorities());
    }
    return false;
  }

  private String authenticatedTenantId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof ConsolePrincipal principal)) {
      return null;
    }
    return principal.tenantId();
  }
}
