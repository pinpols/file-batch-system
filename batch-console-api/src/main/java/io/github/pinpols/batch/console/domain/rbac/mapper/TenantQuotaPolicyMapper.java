package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.param.TenantQuotaPolicyUpdateParam;
import io.github.pinpols.batch.console.domain.param.TenantQuotaPolicyUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface TenantQuotaPolicyMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("policyCode") String policyCode,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("policyCode") String policyCode,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("policyCode") String policyCode);

  int upsertTenantQuotaPolicy(TenantQuotaPolicyUpsertParam param);

  int insert(TenantQuotaPolicyUpsertParam param);

  int update(TenantQuotaPolicyUpdateParam param);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}
