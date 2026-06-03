package com.example.batch.worker.atomic.arch;

import com.example.batch.common.arch.BaseMapperXmlTenantGuardArchTest;

/**
 * batch-worker-atomic 多租 mapper XML 守护(当前模块无 mapper XML,守护占位为新增 XML 时 fail-fast 兜底)。 规则源自
 * batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerAtomicMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {}
