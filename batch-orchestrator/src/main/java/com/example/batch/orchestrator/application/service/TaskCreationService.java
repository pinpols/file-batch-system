package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobTaskEntity;

/**
 * Handles task and step-instance creation.
 * Extracted from {@link DefaultTaskExecutionService}.
 */
public interface TaskCreationService {

    JobTaskEntity createTask(JobTaskEntity task);
}
