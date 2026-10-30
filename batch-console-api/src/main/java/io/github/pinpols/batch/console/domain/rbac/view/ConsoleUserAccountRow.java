package io.github.pinpols.batch.console.domain.rbac.view;

/**
 * batch.console_user_account 管理面行投影（ConsoleUserAccountMapper selectByQuery/selectById/selectByUsername）。
 *
 * <p>固定 9 列，不含 password_hash——账户管理响应与重名预检不触碰密码哈希，哈希只在 {@code findByUsernameIgnoreCase}
 * 认证路径经 {@code ConsoleUserAccountEntity} 读取。created_at/updated_at 保持数据库驱动返回值（Object），
 * 维持历史 {@code resultType=map} 下 {@code String.valueOf} 的响应字符串语义。
 */
public record ConsoleUserAccountRow(
    Long id,
    String tenantId,
    String username,
    String displayName,
    String authoritiesCsv,
    Boolean enabled,
    Boolean mustChangePassword,
    Object createdAt,
    Object updatedAt) {}
