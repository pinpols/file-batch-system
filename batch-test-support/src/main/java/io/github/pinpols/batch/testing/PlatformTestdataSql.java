package io.github.pinpols.batch.testing;

/**
 * 平台库集成/E2E 测试用 SQL 种子在 classpath 上的路径。
 *
 * <p>脚本实体文件随 {@code batch-test-support} 资源包发布，消费模块通过 test scope 依赖从 classpath 加载，避免跨模块源码目录引用。
 */
public final class PlatformTestdataSql {

  private PlatformTestdataSql() {}

  /** t2/t3 多租户种子（与 Flyway 基线后的 batch.* 表结构对齐）。 */
  public static final String MULTI_TENANT_SEED = "classpath:db/testdata/multi-tenant-seed.sql";
}
