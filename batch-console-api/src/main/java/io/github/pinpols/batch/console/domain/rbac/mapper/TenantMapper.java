package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.param.TenantUpsertParam;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface TenantMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(@Param("keyword") String keyword, @Param("status") String status);

  Map<String, Object> selectByTenantId(@Param("tenantId") String tenantId);

  /** R7-A3-P1：批量按 tenant_id 取，替代 batch create / precheck 循环 N+1。 */
  List<Map<String, Object>> selectByTenantIds(@Param("tenantIds") Collection<String> tenantIds);

  int insert(TenantUpsertParam param);

  int update(
      @Param("tenantId") String tenantId,
      @Param("tenantName") String tenantName,
      @Param("description") String description);

  int updateStatus(@Param("tenantId") String tenantId, @Param("status") String status);
}
