package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobTaskEntity;

/** 处理任务与步骤实例的创建，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskCreationService {

  /** 持久化任务实体及其关联的步骤实例，返回含数据库生成 ID 的完整实体。 调用方必须在事务内调用，以保证任务与步骤实例原子可见。 */
  JobTaskEntity createTask(JobTaskEntity task);
}
