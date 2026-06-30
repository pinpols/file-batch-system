package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.AssetFreshnessPolicyRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** JOB asset freshness policy 查询映射。 */
public interface AssetFreshnessPolicyMapper {

  List<AssetFreshnessPolicyRecord> selectEnabledPolicies(@Param("limit") int limit);
}
