package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse.Stage;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse.StatusCount;
import io.github.pinpols.batch.console.domain.job.view.JobExecutionView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobExecutionMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  JobExecutionView selectJob(@Param("tenantId") String tenantId, @Param("id") Long id);

  List<StatusCount> selectCounts(@Param("tenantId") String tenantId, @Param("id") Long id);

  List<Stage> selectStages(
      @Param("tenantId") String tenantId, @Param("pipelineId") Long pipelineId);
}
