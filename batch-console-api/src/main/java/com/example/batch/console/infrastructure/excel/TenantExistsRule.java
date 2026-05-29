package com.example.batch.console.infrastructure.excel;

import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A4 fixture 工程化校验规则:fixture 行涉及的 tenant_id 必须已存在于 batch.tenant 表。
 *
 * <p>背景:sim-e2e 第 2 波发现 ta/tb/tc fixture 跑通后,reconciler / quartz scheduler 仍跳过对应租户的 CRON job, 排查发现
 * batch.tenant 表里没插 ta/tb/tc 三行 ACTIVE 租户。这条规则在 fixture 入口对照 tenant 表存在的 tenant_id 集合,缺则报错并指引用
 * sim-e2e-bootstrap.sql 兜底。
 *
 * <p>纯函数,不依赖 Spring。调用方传入"fixture 涉及租户"与"DB 已存在租户"两个集合。
 */
public final class TenantExistsRule {

  private TenantExistsRule() {}

  /**
   * @param referencedTenantIds 来自所有 sheet 的 tenant_id 唯一集合
   * @param existingTenantIds batch.tenant 表已存在(任何 status)的 tenant_id 集合
   * @return 缺失租户的 issue 列表
   */
  public static List<WorkbookIssue> validate(
      Collection<String> referencedTenantIds, Collection<String> existingTenantIds) {
    List<WorkbookIssue> issues = new ArrayList<>();
    if (referencedTenantIds == null || referencedTenantIds.isEmpty()) {
      return issues;
    }
    Set<String> existing = existingTenantIds == null ? Set.of() : new HashSet<>(existingTenantIds);
    Set<String> missing = new LinkedHashSet<>();
    for (String t : referencedTenantIds) {
      if (t == null || t.isBlank()) {
        continue;
      }
      if (!existing.contains(t)) {
        missing.add(t);
      }
    }
    for (String t : missing) {
      issues.add(
          new WorkbookIssue(
              ConfigPackageExcelValidator.JOB_SHEET,
              0,
              ConfigPackageExcelValidator.COL_TENANT_ID,
              "tenant_id '"
                  + t
                  + "' is referenced by fixture but missing from batch.tenant "
                  + "(reconciler/scheduler will skip CRON jobs); insert it via "
                  + "docs/test-data/sim-e2e-bootstrap.sql or tenant create API"));
    }
    return issues;
  }
}
