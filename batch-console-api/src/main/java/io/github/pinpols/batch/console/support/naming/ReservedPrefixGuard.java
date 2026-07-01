package io.github.pinpols.batch.console.support.naming;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * 命名空间守卫:防止 tenant / resource code 命名残留与高权限冲突。
 *
 * <p><b>校验时机</b>:Service 层 create 入口。不放 DTO @Pattern —— e2e 自身需要 `e2e-` 前缀, prod 反之要拒 `e2e-`
 * 前缀,环境强相关。
 *
 * <p><b>双模式校验(2026-05-21 改造,移除 bypass-mode 豁免)</b>:
 *
 * <pre>
 * | env       | id 形态                    | 结果      | 原因                                |
 * |-----------|---------------------------|-----------|-------------------------------------|
 * | 任何       | system/default/admin      | REJECT    | 永远保留                            |
 * | PROD      | e2e-/qa-/dev-/local-/test- | REJECT    | 防 test 数据污染生产                |
 * | PROD      | 业务命名(bank-corp 等)    | ALLOW     | 正常路径                            |
 * | NON-PROD  | system/default/admin      | REJECT    | 同上                                |
 * | NON-PROD  | DEV_FIXTURE 白名单         | ALLOW     | ta/tb/tc/default-tenant 团队常用    |
 * | NON-PROD  | e2e-/qa-/dev-/local-/test- | ALLOW     | 鼓励 test prefix,残留可一键 cleanup |
 * | NON-PROD  | 无前缀 ID(tx / mytest)       | REJECT    | 防残留:无前缀 = 清不掉(只能跑全 wipe) |
 * </pre>
 *
 * <p>关键改动:**非 prod 不再放任无前缀创建** —— 之前 `bypass-mode=true` 时跳过校验导致 td/te/tx 等 无前缀 ID 残留无主,cleanup
 * endpoint(按 prefix-)无法精确清掉。强制 test prefix 后, e2e 全程 cleanup 链路自闭。
 */
@UtilityClass
public class ReservedPrefixGuard {

  /** 测试 / 环境 / 系统保留前缀 — prod 拒 / 非 prod 鼓励。 */
  public static final List<String> RESERVED_TENANT_PREFIXES =
      List.of("e2e-", "qa-", "dev-", "local-", "test-", "_internal-");

  /** 完全保留的 tenant_id — 系统占用,任何环境都不可重名。 */
  public static final List<String> RESERVED_TENANT_IDS = CommonConstants.RESERVED_TENANT_IDS;

  /**
   * 非 prod 环境 dev fixture 白名单 —— 团队手测 / 部分 e2e 长期复用,允许无 test prefix 直接创建。 增删需评审(参考
   * scripts/db/wipe-non-system-tenants.sql `:keep` 同步)。
   */
  public static final List<String> DEV_FIXTURE_TENANT_IDS = CommonConstants.DEV_FIXTURE_TENANT_IDS;

  /** 资源 code 保留前缀 — 防止业务方误用系统级标识。 */
  public static final List<String> RESERVED_CODE_PREFIXES = List.of("SYSTEM_", "INTERNAL_", "_");

  /**
   * 校验新建租户的 tenantId。
   *
   * @param tenantId 待校验 ID;null/blank 直接放过(其它校验链负责必填)
   * @param productionMode true=生产环境(拒 test prefix);false=非 prod(必须 test prefix 或白名单)
   */
  public static void checkTenantId(String tenantId, boolean productionMode) {
    if (tenantId == null || tenantId.isBlank()) return;
    String lower = tenantId.toLowerCase(Locale.ROOT);

    // 永远拒:RESERVED_TENANT_IDS(system / default / admin)
    if (RESERVED_TENANT_IDS.contains(lower)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.tenant.reserved_id", tenantId);
    }

    if (productionMode) {
      // PROD:拒 test prefix(防 test 数据污染生产)
      for (String prefix : RESERVED_TENANT_PREFIXES) {
        if (lower.startsWith(prefix)) {
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT, "error.tenant.reserved_prefix", tenantId, prefix);
        }
      }
      return;
    }

    // NON-PROD:必须 test prefix 或 DEV_FIXTURE 白名单(防残留无主)
    if (DEV_FIXTURE_TENANT_IDS.contains(lower)) return;
    for (String prefix : RESERVED_TENANT_PREFIXES) {
      if (lower.startsWith(prefix)) return;
    }
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.tenant.non_prod_require_test_prefix", tenantId);
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
