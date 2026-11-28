package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.orchestrator.domain.entity.AssetFreshnessPolicyEntity;

/** JOB asset freshness policy 查询映射。 */
public interface AssetFreshnessPolicyMapper {

    List<AssetFreshnessPolicyEntity> selectEnabledPolicies(@Param("limit") int limit);
}
