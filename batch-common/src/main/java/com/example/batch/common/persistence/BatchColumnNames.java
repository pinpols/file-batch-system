package com.example.batch.common.persistence;

/**
 * 跨模块共用的 DB / Excel 列名常量。
 *
 * <p>动机:`"tenant_id"` 在 BE 25+ 处出现,Excel 列头(导入/导出)+ SQL Map key + mapper xml `#{q.xxx}` 同名,
 * 之前 `COL_TENANT_ID` 在 4 个类里各自定义,改 1 处漏改其余成残留 → 集中常量化。
 *
 * <p>**只放跨模块共用**列名(BatchOps + Console + Worker 都用)。Excel 私有列(`template_type` /
 * `biz_type` 等只在导入/导出某 sheet 出现)继续放各自 Excel schema 类。
 *
 * <p>**不放 metrics tag**(`tenant_id` 作为 prometheus tag 维度)—— 那走 `BatchMetricsNames.TAG_TENANT`
 * 分开管理,语义不同(列名 vs 维度名)。
 */
public final class BatchColumnNames {

  // ── 多租隔离 ─────────────────────────────────────────────────────
  public static final String TENANT_ID = "tenant_id";

  // ── 审计字段(AuditFieldsInterceptor 自动填,query 路径 SELECT 用) ─
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";
  public static final String CREATED_BY = "created_by";
  public static final String UPDATED_BY = "updated_by";

  // ── 软删除(opt-in,详见 docs/coding-conventions.md §9.6) ─────────
  public static final String IS_DELETED = "is_deleted";

  // ── 业务通用字段 ─────────────────────────────────────────────────
  public static final String ID = "id";
  public static final String USERNAME = "username";
  public static final String ENABLED = "enabled";
  public static final String VERSION = "version";

  private BatchColumnNames() {}
}
