package io.github.pinpols.batch.console.domain.rbac.view;

/**
 * batch.tenant 管理面行投影（TenantMapper selectByQuery/selectByTenantId/selectByTenantIds）。
 *
 * <p>固定 8 列。created_at/updated_at 保持数据库驱动返回值（Object），维持历史 {@code resultType=map} 下
 * {@code String.valueOf} 的响应字符串语义。
 */
public record TenantRow(
    Long id,
    String tenantId,
    String tenantName,
    String status,
    String description,
    String createdBy,
    Object createdAt,
    Object updatedAt) {}
