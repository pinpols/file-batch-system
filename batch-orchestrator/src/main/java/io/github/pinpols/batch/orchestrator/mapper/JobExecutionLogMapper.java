package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;

import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobExecutionLogQuery;

public interface JobExecutionLogMapper {

    int insertWithInstanceReferenceGuard(JobExecutionLogEntity entity);

    default int insert(JobExecutionLogEntity entity) {
        int inserted = insertWithInstanceReferenceGuard(entity);
        if (inserted != 1) {
            throw new IllegalStateException("Job execution log references a missing or different-tenant JobInstance");
        }
        return inserted;
    }

    List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);
}
