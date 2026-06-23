package io.github.pinpols.batch.worker.core.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;

/**
 * batch-worker-core 多租 mapper XML 守护。规则源自 batch-common test-jar 的 {@link
 * BaseMapperXmlTenantGuardArchTest}——防止 worker-core mapper 退化出可空 {@code <if tenantId>} 租户守护。
 */
class WorkerCoreMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {}
