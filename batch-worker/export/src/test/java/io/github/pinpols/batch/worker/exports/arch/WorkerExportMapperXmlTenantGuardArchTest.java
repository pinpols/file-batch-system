package io.github.pinpols.batch.worker.exports.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;

/**
 * batch-worker-export 多租 mapper XML 守护(当前模块无 mapper XML,守护占位为新增 XML 时 fail-fast 回退)。 规则源自
 * batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerExportMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {}
