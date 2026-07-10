package io.github.pinpols.batch.worker.processes.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.Set;

/**
 * batch-worker-process 多租 mapper XML 守护。规则源自 batch-common test-jar 的 {@link
 * BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerProcessMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /** batch.* UPDATE/DELETE 缺 tenant_id 谓词的语句级豁免。红线:新写严禁往此追加。 */
  @Override
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of(
        // batch.process_staging 是 RLS 覆盖表(CLAUDE.md:RLS 覆盖 biz.* + batch.process_staging),
        // 租户隔离由 SET LOCAL app.tenant_id 行级策略强制;此为按 staged_at 的孤儿 GC(内部维护)
        "ProcessStagingMapper#deleteOrphansOlderThan");
  }
}
