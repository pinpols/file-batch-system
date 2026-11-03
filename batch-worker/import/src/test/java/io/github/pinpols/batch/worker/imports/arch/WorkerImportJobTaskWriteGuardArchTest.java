package io.github.pinpols.batch.worker.imports.arch;

import io.github.pinpols.batch.common.arch.BaseWorkerJobTaskWriteGuardArchTest;

/** batch-worker-import 不得越过登记列写 Orchestrator 所有的 {@code batch.job_task}。 */
class WorkerImportJobTaskWriteGuardArchTest extends BaseWorkerJobTaskWriteGuardArchTest {}
