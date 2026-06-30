package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.infrastructure.lineage.OpenLineageDatasetRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** OpenLineage emitter 使用的只读 dataset 查询。 */
public interface OpenLineageDatasetMapper {

  /**
   * 以 workflow_run 为入口，收集 BFS 文件级 dataset。
   *
   * <p>仅覆盖热表中的 job_instance.related_file_id、pipeline_instance.file_id、同 trace_id file_record。
   * 字段级/记录级血缘不在本 mapper 范围内。
   */
  List<OpenLineageDatasetRow> selectWorkflowDatasets(
      @Param("tenantId") String tenantId,
      @Param("relatedJobInstanceId") Long relatedJobInstanceId,
      @Param("traceId") String traceId);
}
