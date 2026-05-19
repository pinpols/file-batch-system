package com.example.batch.console.support.naming;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * 命名空间守卫:拒绝用户用保留前缀创建 tenant / resource code,避免 e2e 测试与生产数据混淆, 也避免普通用户冒用 system / admin 这类高权限标识。
 *
 * <p>校验时机:Service 层的 create 入口(不要放 DTO @Pattern,因为 e2e 自身需要用 `e2e-` 前缀 创建临时租户)。production profile
 * 启用时由 application.yml 注入,本地 / e2e 默认关。
 *
 * <p>策略:**只拒新建**,老数据不动。这样:
 *
 * <ul>
 *   <li>历史脏 tenant_id(如 `default` / `ta` / `e2e-1779...`)不会被强行 rename
 *   <li>e2e 测试用 `e2e-` 前缀依然合法(测试环境守卫关闭)
 *   <li>生产环境一旦开启,所有新建必须走业务命名(`bank-corp` / `mall-mvp` 这种)
 * </ul>
 */
@UtilityClass
public class ReservedPrefixGuard {

  /** 测试 / 环境 / 系统保留前缀 — 任何用户(包括 admin)在生产环境创建都拒。 */
  public static final List<String> RESERVED_TENANT_PREFIXES =
      List.of("e2e-", "qa-", "dev-", "local-", "test-", "_internal-");

  /** 完全保留的 tenant_id — 系统占用,不可重名。 */
  public static final List<String> RESERVED_TENANT_IDS = List.of("system", "default", "admin");

  /** 资源 code 保留前缀 — 防止业务方误用系统级标识。 */
  public static final List<String> RESERVED_CODE_PREFIXES = List.of("SYSTEM_", "INTERNAL_", "_");

  /**
   * 校验新建租户的 tenantId 不撞保留前缀 / 保留字。
   *
   * <p>strict=false 时不抛(本地 / e2e 用),strict=true 时拒。
   */
  public static void checkTenantId(String tenantId, boolean strict) {
    if (!strict || tenantId == null || tenantId.isBlank()) return;
    String lower = tenantId.toLowerCase();
    if (RESERVED_TENANT_IDS.contains(lower)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.tenant.reserved_id", tenantId);
    }
    for (String prefix : RESERVED_TENANT_PREFIXES) {
      if (lower.startsWith(prefix)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.tenant.reserved_prefix", tenantId, prefix);
      }
    }
  }

  /**
   * 校验资源 code 不撞保留前缀。
   *
   * <p>仅 strict 模式生效。建议 production profile 开启,test/local 关。
   */
  public static void checkResourceCode(String code, boolean strict) {
    if (!strict || code == null || code.isBlank()) return;
    String upper = code.toUpperCase();
    for (String prefix : RESERVED_CODE_PREFIXES) {
      if (upper.startsWith(prefix)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.resource.reserved_prefix", code, prefix);
      }
    }
  }
}
