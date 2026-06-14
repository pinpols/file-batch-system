package com.example.batch.common.tenant.routing;

import com.example.batch.common.rls.RlsTenantContextHolder;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 按当前租户把 biz 连接路由到对应 placement 数据源(pooled 分片 / silo)。
 *
 * <p>lookup key = {@link BusinessPlacementResolver#resolve(String)}(以 {@link
 * RlsTenantContextHolder#get()} 当前租户为输入)。targetDataSources(各 shard/silo)与 defaultTargetDataSource
 * 由装配方(各 worker 的 BusinessDataSourceConfiguration)在构造时设入。
 *
 * <p><b>事务约束</b>:Spring 在事务开始时绑定一条连接,故路由 key 在 tx 内必须稳定 —— 租户上下文 必须在 {@code @Transactional} 之前设好、tx
 * 内不变。worker"一任务一租户、入口设上下文"天然满足。 单数据源本地事务即可,无需 XA(biz 永不跨租户)。
 */
public class BusinessRoutingDataSource extends AbstractRoutingDataSource {

  private final transient BusinessPlacementResolver resolver;

  public BusinessRoutingDataSource(BusinessPlacementResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  protected Object determineCurrentLookupKey() {
    return resolver.resolve(RlsTenantContextHolder.get());
  }
}
