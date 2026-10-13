package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.query.FilePipelineStepRunQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineStepRunMapper {

  String selectTenantIdByPipelineInstanceId(@Param("pipelineInstanceId") Long pipelineInstanceId);

  List<Map<String, Object>> selectByQuery(FilePipelineStepRunQuery query);

  long countByQuery(FilePipelineStepRunQuery query);

  List<Map<String, Object>> selectProgressByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);

  /** 缺口2:返回 pipeline 级文件信息(file_id + file_name),文件缺失时 file_name 为 null。 */
  Map<String, Object> selectFileInfoByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);
}
