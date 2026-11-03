package io.github.pinpols.batch.worker.exports.arch;

import io.github.pinpols.batch.common.arch.BaseWorkerJobTaskWriteGuardArchTest;

/** batch-worker-export 不得越过登记列写 Orchestrator 所有的 {@code batch.job_task}。 */
class WorkerExportJobTaskWriteGuardArchTest extends BaseWorkerJobTaskWriteGuardArchTest {}
