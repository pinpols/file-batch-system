package io.github.pinpols.batch.console.domain.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionNodeView;
import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionSnapshotView;

public interface WorkflowExecutionMapper {
    String setTenantContext(@Param("tenantId") String tenantId);

    WorkflowExecutionSnapshotView selectSnapshot(@Param("tenantId") String tenantId, @Param("id") Long id);

    List<WorkflowExecutionNodeView> selectLatestNodes(@Param("tenantId") String tenantId, @Param("id") Long id);
}
