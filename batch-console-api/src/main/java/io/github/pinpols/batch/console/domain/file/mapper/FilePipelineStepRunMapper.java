package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineStepRunMapper {

  String selectTenantIdByPipelineInstanceId(@Param("pipelineInstanceId") Long pipelineInstanceId);

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") Long pipelineInstanceId,
      @Param("stepCode") String stepCode,
      @Param("stageCode") String stageCode,
      @Param("stepStatus") String stepStatus,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") Long pipelineInstanceId,
      @Param("stepCode") String stepCode,
      @Param("stageCode") String stageCode,
      @Param("stepStatus") String stepStatus);

  List<Map<String, Object>> selectProgressByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);
}
