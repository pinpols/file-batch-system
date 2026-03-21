package com.example.batch.orchestrator.config;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.kafka.BatchTopics;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "batch.mq.topics")
public class BatchMqTopicsProperties {

    private String importDispatch = BatchTopics.TASK_DISPATCH_IMPORT;
    private String exportDispatch = BatchTopics.TASK_DISPATCH_EXPORT;
    private String dispatchDispatch = BatchTopics.TASK_DISPATCH_DISPATCH;
    private String taskResult = BatchTopics.TASK_RESULT;
    private String deadLetter = BatchTopics.TASK_DEAD_LETTER;
    private String taskRetry = BatchTopics.TASK_RETRY;

    public String resolveDispatchTopic(String workerType) {
        if (!StringUtils.hasText(workerType)) {
            return null;
        }
        if (JobType.IMPORT.code().equalsIgnoreCase(workerType)) {
            return importDispatch;
        }
        if (JobType.EXPORT.code().equalsIgnoreCase(workerType)) {
            return exportDispatch;
        }
        if (JobType.DISPATCH.code().equalsIgnoreCase(workerType)) {
            return dispatchDispatch;
        }
        return null;
    }
}
