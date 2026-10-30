package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobExecutionLogQuery;
import java.util.List;

public interface JobExecutionLogMapper {

  int insertWithInstanceReferenceGuard(JobExecutionLogEntity entity);

  default int insert(JobExecutionLogEntity entity) {
    int inserted = insertWithInstanceReferenceGuard(entity);
    if (inserted != 1) {
      throw new IllegalStateException(
          "Job execution log references a missing or different-tenant JobInstance");
    }
    return inserted;
  }

  List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);
}
