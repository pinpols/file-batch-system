package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobTaskEntity;

/** 处理任务与步骤实例的创建，从 {@link DefaultTaskExecutionService} 中拆分。 */
public interface TaskCreationService {

    JobTaskEntity createTask(JobTaskEntity task);
}
