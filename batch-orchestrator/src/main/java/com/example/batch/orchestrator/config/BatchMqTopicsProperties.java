package com.example.batch.orchestrator.config;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.Texts;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.mq.topics")
public class BatchMqTopicsProperties {

  private String importDispatch = BatchTopics.TASK_DISPATCH_IMPORT;
  private String exportDispatch = BatchTopics.TASK_DISPATCH_EXPORT;
  private String processDispatch = BatchTopics.TASK_DISPATCH_PROCESS;
  private String dispatchDispatch = BatchTopics.TASK_DISPATCH_DISPATCH;
  // ADR-029:原子任务(ATOMIC)派发 topic,专用 batch-worker-atomic 消费。
  private String atomicDispatch = BatchTopics.TASK_DISPATCH_ATOMIC;
  private String taskResult = BatchTopics.TASK_RESULT;
  private String deadLetter = BatchTopics.TASK_DEAD_LETTER;
  private String taskRetry = BatchTopics.TASK_RETRY;

  public String resolveDispatchTopic(String workerType) {
    if (!Texts.hasText(workerType)) {
      return null;
    }
    if (JobType.IMPORT.code().equalsIgnoreCase(workerType)) {
      return importDispatch;
    }
    if (JobType.EXPORT.code().equalsIgnoreCase(workerType)) {
      return exportDispatch;
    }
    if (JobType.PROCESS.code().equalsIgnoreCase(workerType)) {
      return processDispatch;
    }
    if (JobType.DISPATCH.code().equalsIgnoreCase(workerType)) {
      return dispatchDispatch;
    }
    if (JobType.ATOMIC.code().equalsIgnoreCase(workerType)) {
      return atomicDispatch;
    }
    return null;
  }
}
