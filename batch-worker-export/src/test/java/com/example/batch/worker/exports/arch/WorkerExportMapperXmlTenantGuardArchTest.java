package com.example.batch.worker.exports.arch;

import com.example.batch.common.arch.BaseMapperXmlTenantGuardArchTest;

/**
 * batch-worker-export 多租 mapper XML 守护(当前模块无 mapper XML,守护占位为新增 XML 时 fail-fast 兜底)。 规则源自
 * batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest}。
 */
class WorkerExportMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {}
