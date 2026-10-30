package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/** {@code batch.asset_freshness_policy} Console 管理映射。 */
public interface ConsoleAssetFreshnessPolicyMapper {

  List<AssetFreshnessPolicyEntity> findByTenant(
      @Param("tenantId") String tenantId,
      @Param("assetCode") String assetCode,
      @Param("enabled") Boolean enabled,
      @Param("page") PageRequest page);

  long countByTenant(
      @Param("tenantId") String tenantId,
      @Param("assetCode") String assetCode,
      @Param("enabled") Boolean enabled);

  Optional<AssetFreshnessPolicyEntity> findById(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  Long lockJobDefinitionForPolicyWrite(
      @Param("tenantId") String tenantId, @Param("jobCode") String jobCode);

  void upsert(@Param("p") AssetFreshnessPolicyUpsertParam param);

  int updateById(@Param("p") AssetFreshnessPolicyUpsertParam param);

  int updateEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") boolean enabled);
}
