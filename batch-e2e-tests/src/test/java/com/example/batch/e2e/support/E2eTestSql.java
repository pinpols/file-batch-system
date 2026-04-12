package com.example.batch.e2e.support;

import com.example.batch.testing.PlatformTestdataSql;

/** E2E {@link org.springframework.test.context.jdbc.Sql} 脚本路径（单源，避免各 IT 手写 classpath 分叉）。 */
public final class E2eTestSql {

  private E2eTestSql() {}

  /**
   * 业务库表结构（单源：{@code docs/sql/business/create_biz_tables.sql}，Maven 打进 {@code classpath:sql/}；非
   * Flyway）。
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
