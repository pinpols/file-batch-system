package com.example.batch.testing;

/**
 * 平台库集成/E2E 测试用 SQL 种子在 classpath 上的路径。
 * <p>脚本实体文件由 {@code batch-e2e-tests} 维护（{@code src/test/resources/db/testdata/}），
 * orchestrator 等模块通过 Maven {@code testResource} 引入同一路径，避免重复副本。
 */
public final class PlatformTestdataSql {

    private PlatformTestdataSql() {}

    /** t2/t3 多租户种子（与 Flyway 基线后的 batch.* 表结构对齐）。 */
    public static final String MULTI_TENANT_SEED = "classpath:db/testdata/multi-tenant-seed.sql";
}
