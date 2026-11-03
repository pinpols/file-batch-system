package io.github.pinpols.batch.worker.processes.arch;

import io.github.pinpols.batch.common.arch.BaseWorkerJobTaskWriteGuardArchTest;
import java.util.Set;

/** batch-worker-process 不得越过登记列写 Orchestrator 所有的 {@code batch.job_task}。 */
class WorkerProcessJobTaskWriteGuardArchTest extends BaseWorkerJobTaskWriteGuardArchTest {
  /** 见 runtime-module-communication.md「Worker 可写的 job_task 列」;写入均带 RUNNING/invocation 谓词。 */
  @Override
  protected Set<String> allowedJobTaskColumns() {
    return Set.of("process_staging_generation_id", "process_staging_batch_key");
  }
}
