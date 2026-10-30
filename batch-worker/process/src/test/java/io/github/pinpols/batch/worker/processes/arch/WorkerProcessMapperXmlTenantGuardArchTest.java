package io.github.pinpols.batch.worker.processes.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.Set;

/**
 * batch-worker-process 多租 mapper XML 守护。规则源自 batch-test-support 的 {@link
 * BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerProcessMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /** batch.* UPDATE/DELETE 缺 tenant_id 谓词的语句级豁免。红线:新写严禁往此追加。 */
  @Override
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of();
  }
}
