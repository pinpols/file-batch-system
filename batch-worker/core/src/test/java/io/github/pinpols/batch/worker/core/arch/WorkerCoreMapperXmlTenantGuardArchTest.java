package io.github.pinpols.batch.worker.core.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.Set;

/**
 * batch-worker-core 多租 mapper XML 守护。规则源自 batch-common test-jar 的 {@link
 * BaseMapperXmlTenantGuardArchTest}——防止 worker-core mapper 退化出可空 {@code <if tenantId>} 租户守护。
 */
class WorkerCoreMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /**
   * batch.* UPDATE/DELETE 缺 tenant_id 谓词的语句级豁免。worker-core 是执行进程,按已认领的 run id / 全局 outbox id
   * 写,非用户可达。红线:新写严禁往此追加。
   */
  @Override
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of(
        // worker 执行:按已认领的 pipeline_instance id 推进流水线运行态(id 由认领任务下发,worker 是系统组件)
        "PlatformFileRuntimeMapper#bindFileToPipelineInstance",
        "PlatformFileRuntimeMapper#updatePipelineStage",
        "PlatformFileRuntimeMapper#markPipelineSuccess",
        "PlatformFileRuntimeMapper#markPipelineFailed",
        "PlatformFileRuntimeMapper#markPipelineCompensating",
        // file_record 有 tenant_id 列,但 worker 按已认领的 file_record id 推进该文件运行态(id 由认领任务下发)
        "PlatformFileRuntimeMapper#updateFileRecordStatus",
        "PlatformFileRuntimeMapper#updateFileRecordMetadata",
        // report 发件箱发布器状态机:全局轮询后按全局行 id 推进;resetStalePublishing 为跨租 reaper
        "WorkerReportOutboxPgMapper#updateGiveUp",
        "WorkerReportOutboxPgMapper#updateRetry",
        "WorkerReportOutboxPgMapper#giveUpRow",
        "WorkerReportOutboxPgMapper#deleteById",
        "WorkerReportOutboxPgMapper#resetStalePublishing");
  }
}
