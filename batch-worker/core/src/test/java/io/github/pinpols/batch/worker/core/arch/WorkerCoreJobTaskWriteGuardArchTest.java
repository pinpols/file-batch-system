package io.github.pinpols.batch.worker.core.arch;

import io.github.pinpols.batch.common.arch.BaseWorkerJobTaskWriteGuardArchTest;

/** batch-worker-core 不得越过登记列写 Orchestrator 所有的 {@code batch.job_task}。 */
class WorkerCoreJobTaskWriteGuardArchTest extends BaseWorkerJobTaskWriteGuardArchTest {}
