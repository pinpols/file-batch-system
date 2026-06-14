package com.example.batch.console.service;

import com.example.batch.console.domain.entity.BusinessShardCatalogEntity;
import com.example.batch.console.domain.param.BusinessShardCatalogUpsertParam;
import com.example.batch.console.mapper.ConsoleBusinessShardCatalogMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * biz 分片目录管理(P2 tenant-routing)。平台 ROLE_ADMIN 登记/列出/删片(只登记位置不存账密)。
 *
 * <p>是 placement 指派 key 白名单的权威来源({@link #enabledKeys()}),也是前端「分片列表」视图数据源。 写 {@code
 * batch.business_shard_catalog}(platform 库)。改本表不动运行中 worker 的连接池(那需重启重建)。
 */
@Service
@RequiredArgsConstructor
public class ConsoleBusinessShardCatalogService {

  private final ConsoleBusinessShardCatalogMapper catalogMapper;

  public List<BusinessShardCatalogEntity> list() {
    return catalogMapper.findAll();
  }

  /** enabled 的 placement key 集合(placement 指派白名单)。 */
  public List<String> enabledKeys() {
    return catalogMapper.findEnabledKeys();
  }

  @Transactional
  public void upsert(BusinessShardCatalogUpsertParam param) {
    catalogMapper.upsert(param);
  }

  /** 删片登记;返回是否实际删除(幂等)。 */
  @Transactional
  public boolean delete(String placementKey) {
    return catalogMapper.deleteByKey(placementKey) > 0;
  }
}
