package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.common.enums.ApprovalCommandStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ConfigApprovalMapper {

  Map<String, Object> selectLatestByRelease(
      @Param("tenantId") String tenantId, @Param("releaseId") Long releaseId);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int insert(Map<String, Object> params);

  default int approve(Map<String, Object> params) {
    return transition(withStatuses(params, ApprovalCommandStatus.APPROVED));
  }

  default int reject(Map<String, Object> params) {
    return transition(withStatuses(params, ApprovalCommandStatus.REJECTED));
  }

  int transition(Map<String, Object> params);

  private static Map<String, Object> withStatuses(
      Map<String, Object> params, ApprovalCommandStatus targetStatus) {
    Map<String, Object> transition = new LinkedHashMap<>(params);
    transition.put("targetStatus", targetStatus.code());
    transition.put("expectedStatus", ApprovalCommandStatus.PENDING.code());
    return transition;
  }
}
