package com.example.batch.console.domain.rbac.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;

/**
 * 租户作用域纵深加固工具。
 *
 * <p><b>背景</b>:部分 mapper（{@code WorkflowNodeMapper} / {@code WorkflowEdgeMapper} / {@code
 * FileArrivalGroupMapper} / {@code OperationAuditMapper} 等）的查询 XML 用可空 {@code <if test="tenantId !=
 * null">AND tenant_id = #{tenantId}</if>} 守护——租户参数为 null/空时**退化成全租户扫描**。 这些 mapper 同时服务两类调用路径:
 *
 * <ul>
 *   <li><b>租户作用域</b>:面向单租用户的列表/分页查询,tenantId 必须先经 {@link ConsoleTenantGuard#resolveTenant(String)}
 *       解析为非空值;
 *   <li><b>definition-id 作用域</b>(合法的 null tenantId):配置导出/复制时按已经过租户过滤的 {@code
 *       workflow_definition_id} 反查 node/edge,tenantId 故意为 null,靠 FK 收敛——**不在本工具约束范围**。
 * </ul>
 *
 * <p>当前所有租户作用域调用点都已走 {@code resolveTenant}(返回值恒非空),但**无运行时强制**:未来新调用方若漏掉 resolveTenant 直接把可空 {@code
 * request.getTenantId()} 传进这些 mapper,会静默跨租扫描且既有测试不变红。 本工具是**最后一道运行时断言**——在租户作用域把 tenantId 下发 mapper
 * 前调用,null/空即 fail-fast, 而非静默越权。
 *
 * <p><b>注意</b>:仅用于**租户作用域**调用点。global/admin 跨租路径(故意不传 tenantId 查全租)与 definition-id 作用域(按父 id
 * 反查)**禁止**调用本方法,否则会误伤合法路径。
 */
public final class TenantScope {

  private TenantScope() {}

  /**
   * 断言租户作用域 tenantId 非空,返回原值以便链式使用。
   *
   * @param tenantId 已经过 {@link ConsoleTenantGuard#resolveTenant(String)} 解析的租户 id
   * @return 非空的 tenantId
   * @throws BizException null / 空 / 全空白 → {@code FORBIDDEN error.tenant.context_missing}
   */
  public static String requireTenant(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.tenant.context_missing");
    }
    return tenantId;
  }
}
