package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyRule;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobDependencyMapper {

  List<String> selectUpstreamJobCodes(
      @Param("tenantId") String tenantId, @Param("downstreamJobCode") String downstreamJobCode);

  List<JobDependencyRule> selectUpstreamRules(
      @Param("tenantId") String tenantId, @Param("downstreamJobCode") String downstreamJobCode);

  String selectCalendarCodeByTenantAndJob(
      @Param("tenantId") String tenantId, @Param("jobCode") String jobCode);

  Long selectJobDefinitionIdByTenantAndJob(
      @Param("tenantId") String tenantId, @Param("jobCode") String jobCode);
}
