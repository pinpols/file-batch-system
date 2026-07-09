package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.List;

/** 处理任务与步骤实例的创建，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskCreationService {

  /** 持久化任务实体及其关联的步骤实例，返回含数据库生成 ID 的完整实体。 调用方必须在事务内调用，以保证任务与步骤实例原子可见。 */
  JobTaskEntity createTask(JobTaskEntity task);

  /**
   * PERF(5.1): launch fan-out 批量创建 —— 语义等价于对每个元素调 {@link #createTask}，但 task 与 step 镜像各走一次多行
   * INSERT（2N+N 次 SQL → 2 次）。所有元素先统一校验（task_type fail-fast），任何一项非法则整批不写。 调用方必须在事务内调用。
   */
  List<JobTaskEntity> createTasks(List<JobTaskEntity> tasks);
}
