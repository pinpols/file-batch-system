package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.query.FilePipelineStepRunQuery;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineExecutionView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineInfoView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineProgressStepView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineStepRunQueryView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineStepRunMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  FilePipelineExecutionView selectExecution(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);

  List<FilePipelineProgressStepView> selectLatestProgressByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);

  String selectTenantIdByPipelineInstanceId(@Param("pipelineInstanceId") Long pipelineInstanceId);

  List<FilePipelineStepRunQueryView> selectByQuery(FilePipelineStepRunQuery query);

  long countByQuery(FilePipelineStepRunQuery query);

  List<FilePipelineProgressStepView> selectProgressByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);

  /** 返回流水线关联的文件信息；文件记录不存在时，文件名为 null。 */
  FilePipelineInfoView selectFileInfoByPipelineInstance(
      @Param("tenantId") String tenantId, @Param("pipelineInstanceId") Long pipelineInstanceId);
}
