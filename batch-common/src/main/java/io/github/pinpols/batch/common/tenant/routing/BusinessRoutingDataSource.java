package io.github.pinpols.batch.common.tenant.routing;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
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
public class BusinessRoutingDataSource extends AbstractRoutingDataSource implements AutoCloseable {

  private final BusinessPlacementResolver resolver;
  private final Set<DataSource> ownedDataSources;
  private final AtomicBoolean closed = new AtomicBoolean();

  public BusinessRoutingDataSource(BusinessPlacementResolver resolver) {
    this(resolver, List.of());
  }

  public BusinessRoutingDataSource(
      BusinessPlacementResolver resolver, Collection<? extends DataSource> ownedDataSources) {
    this.resolver = resolver;
    Set<DataSource> uniqueDataSources = Collections.newSetFromMap(new IdentityHashMap<>());
    uniqueDataSources.addAll(ownedDataSources);
    this.ownedDataSources = Collections.unmodifiableSet(uniqueDataSources);
  }

  @Override
  protected Object determineCurrentLookupKey() {
    return resolver.resolve(RlsTenantContextHolder.get());
  }

  /**
   * 关闭路由器拥有的真实连接池。
   *
   * <p>分片池由 {@code BusinessDataSourceBuilder} 直接创建，并不是独立 Spring Bean；因此必须由路由 Bean
   * 统一负责释放。按对象身份去重，避免默认数据源同时出现在 targets/default 时被重复关闭。
   */
  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Exception firstFailure = null;
    for (DataSource dataSource : ownedDataSources) {
      if (!(dataSource instanceof AutoCloseable closeable)) {
        continue;
      }
      try {
        closeable.close();
      } catch (Exception ex) {
        if (EmptyChecks.isNull(firstFailure)) {
          firstFailure = ex;
        } else {
          firstFailure.addSuppressed(ex);
        }
      }
    }
    if (EmptyChecks.isNotNull(firstFailure)) {
      throw firstFailure;
    }
  }
}
