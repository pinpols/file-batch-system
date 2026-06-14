package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.BusinessShardCatalogEntity;
import com.example.batch.console.domain.param.BusinessShardCatalogUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.business_shard_catalog} 管理 mapper(console)。biz 分片目录:登记/列出/删片。
 *
 * <p>平台 ROLE_ADMIN 跨片维护,无 tenant 维度(分片拓扑是系统配置)。{@link #findEnabledKeys()} 供 placement 指派的 key
 * 白名单校验。
 */
public interface ConsoleBusinessShardCatalogMapper {

  /** 全表列出(前端「分片列表」视图)。 */
  List<BusinessShardCatalogEntity> findAll();

  /** enabled=true 的 placement key 集合(placement 指派白名单源)。 */
  List<String> findEnabledKeys();

  /** ON CONFLICT (placement_key) DO UPDATE 语义。 */
  void upsert(@Param("p") BusinessShardCatalogUpsertParam p);

  /** 删片(目录登记),返回删除行数。 */
  int deleteByKey(@Param("placementKey") String placementKey);
}
