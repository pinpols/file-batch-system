package com.example.batch.orchestrator.mapper;

import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.query.WorkflowRunQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowRunMapper {

  int insert(WorkflowRunEntity entity);

  WorkflowRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  WorkflowRunEntity selectByRelatedJobInstanceId(
      @Param("tenantId") String tenantId, @Param("relatedJobInstanceId") Long relatedJobInstanceId);

  List<WorkflowRunEntity> selectByQuery(WorkflowRunQuery query);

  int updateStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("runStatus") String runStatus,
      @Param("currentNodeCode") String currentNodeCode,
      @Param("finishedAt") Instant finishedAt);

  int markRunning(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("runStatus") String runStatus,
      @Param("currentNodeCode") String currentNodeCode,
      @Param("startedAt") Instant startedAt);

  /** P3-3 archive: 选出终结态、finished_at 早于 cutoff 的 workflow_run id（带 limit 防长事务）。 */
  List<Long> selectArchivableIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

  /** P3-3 archive: 按 id 列表删除 workflow_node_run（FK 子表），返回删除行数。 */
  int deleteNodeRunsByWorkflowRunIds(@Param("ids") List<Long> workflowRunIds);

  /** P3-3 archive: 按 id 列表删除 workflow_run，返回删除行数。 */
  int deleteByIds(@Param("ids") List<Long> ids);
}
