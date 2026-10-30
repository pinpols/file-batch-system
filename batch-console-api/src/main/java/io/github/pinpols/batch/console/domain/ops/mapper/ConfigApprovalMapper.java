package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalInsertParam;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalTransitionParam;
import io.github.pinpols.batch.console.domain.ops.view.ConfigApprovalRow;
import org.apache.ibatis.annotations.Param;

public interface ConfigApprovalMapper {

  ConfigApprovalRow selectLatestByRelease(
      @Param("tenantId") String tenantId, @Param("releaseId") Long releaseId);

  ConfigApprovalRow selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int insert(@Param("param") ConfigApprovalInsertParam param);

  int transition(@Param("param") ConfigApprovalTransitionParam param);
}
