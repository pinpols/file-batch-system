package io.github.pinpols.batch.e2e.support;

import io.github.pinpols.batch.testing.PlatformTestdataSql;

/** E2E SQL 脚本路径（单源，避免各 IT 手写 classpath 分叉）。 */
public final class E2eTestSql {

  private E2eTestSql() {}

  /**
   * 业务库表结构（单源：{@code docs/sql/business/create_biz_tables.sql}，Maven 打进 {@code classpath:sql/}；非
   * Flyway）。测试类应通过 {@code @E2eBusinessSchema} 显式绑定独立的 {@code e2eBusinessDataSource}，不要使用
   * 平台库主数据源执行该脚本。
   */
  public static final String BIZ_SCHEMA = "classpath:sql/create_biz_tables.sql";

  public static final String IMPORT_TEMPLATE_SEED =
      "classpath:db/testdata/import-template-config-seed.sql";

  public static final String EXPORT_TEMPLATE_SEED =
      "classpath:db/testdata/export-template-config-seed.sql";

  /**
   * 平台库 t2/t3 多租户种子（{@code batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql}）。
   */
  public static final String MULTI_TENANT_SEED = PlatformTestdataSql.MULTI_TENANT_SEED;
}
