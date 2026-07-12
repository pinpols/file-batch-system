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

  /** 缺口2:返回 pipeline 级文件信息(file_id + file_name),文件缺失时 file_name 为 null。 */
  Map<String, Object> selectFileInfoByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);

  /** 缺口1:解析该 pipeline 当前运行中分区的 worker_code,无运行态分区返回 null。 */
  String selectRunningWorkerCode(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);
}
