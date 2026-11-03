package io.github.pinpols.batch.worker.dispatch.arch;

import io.github.pinpols.batch.common.arch.BaseWorkerJobTaskWriteGuardArchTest;
import java.util.Set;

/** batch-worker-dispatch 不得越过登记列写 Orchestrator 所有的 {@code batch.job_task}。 */
class WorkerDispatchJobTaskWriteGuardArchTest extends BaseWorkerJobTaskWriteGuardArchTest {
  /** 见 runtime-module-communication.md「Worker 可写的 job_task 列」;写入均带 RUNNING/invocation 谓词。 */
  @Override
  protected Set<String> allowedJobTaskColumns() {
    return Set.of("external_outcome_resolution_consumed_at");
  }
}
