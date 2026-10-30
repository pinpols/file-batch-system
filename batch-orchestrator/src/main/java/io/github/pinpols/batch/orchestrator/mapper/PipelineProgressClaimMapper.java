package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.view.PipelineProgressClaimView;
import io.github.pinpols.batch.orchestrator.domain.view.PipelineProgressStageView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PipelineProgressClaimMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  List<PipelineProgressClaimView> selectLiveClaims(
      @Param("tenantId") String tenantId, @Param("pipelineId") Long pipelineId);

  List<PipelineProgressStageView> selectRunningStages(
      @Param("tenantId") String tenantId, @Param("pipelineId") Long pipelineId);
}
