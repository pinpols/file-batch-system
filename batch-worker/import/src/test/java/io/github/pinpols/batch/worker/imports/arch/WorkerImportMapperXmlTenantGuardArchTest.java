package io.github.pinpols.batch.worker.imports.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.HashSet;
import java.util.Set;

/**
 * batch-worker-import 多租 mapper XML 守护(当前模块无 mapper XML,守护占位为新增 XML 时 fail-fast 回退)。 规则源自
 * batch-test-support 的 {@link BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerImportMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {
  @Override
  protected Set<String> tenantExemptTables() {
    Set<String> tables = new HashSet<>(super.tenantExemptTables());
    // V377 按物理存储和扫描范围协调跨租户对象，不含 tenant_id；仅授权 Import 角色。
    tables.add("import_ingress_scan_storage_lease");
    tables.add("import_ingress_scan_state");
    tables.add("import_ingress_scan_object");
    return tables;
  }
}
