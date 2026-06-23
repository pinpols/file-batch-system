package io.github.pinpols.batch.worker.atomic.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;

/**
 * batch-worker-atomic 多租 mapper XML 守护(当前模块无 mapper XML,守护占位为新增 XML 时 fail-fast 回退)。 规则源自
 * batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerAtomicMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {}
