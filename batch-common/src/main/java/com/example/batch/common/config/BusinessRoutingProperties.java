package com.example.batch.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2 tenant-routing 多片配置（{@code batch.datasource.business.routing}）。
 *
 * <p>默认 {@code enabled=false} → worker 走单片(shard-0=现库,零行为变更)。打开后按 {@code shards}(每片一组 凭据,来源
 * secrets/biz-shards/ 经 env 注入,<b>不存 placement 表</b>)+ {@link
 * com.example.batch.common.tenant.routing.HashAndSiloPlacementResolver} 路由:租户 hash 进 {@code
 * pooledShardCount} 个池化片,或经 {@code siloOverrides} 独占某 silo。
 *
 * <p>典型 env 绑定(Spring relaxed binding):
 *
 * <pre>
 * BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED=true
 * BATCH_DATASOURCE_BUSINESS_ROUTING_POOLED_SHARD_COUNT=2
 * BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_KEY=shard-0
 * BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_URL=jdbc:postgresql://h0:5432/batch_business
 * BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_USERNAME=...
 * BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_PASSWORD=...
 * BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_KEY=shard-1
 * ...
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "batch.datasource.business.routing")
public class BusinessRoutingProperties {

  /** 多片路由总开关。false(默认)=单片无损;true=按 shards 装配 multiShard。 */
  private boolean enabled = false;

  /** placement 来源:CONFIG(默认,hash + siloOverrides)或 TABLE(表覆盖 + hash 回退)。 */
  private PlacementSource placementSource = PlacementSource.CONFIG;

  /**
   * TABLE 模式下 placement 映射整表缓存 TTL(ms),过期触发重载;迁片登记最迟一个 TTL 生效。
   *
   * <p>⚠️ 设为 0 = 每次路由决策(每次 getConnection)都查 platform 库,会把 platform 库负载与 biz 路由延迟直接耦合,
   * 仅测试用;生产保持秒级(默认 5s)。
   */
  private long placementCacheTtlMs = 5000L;

  /** 池化片数量(hash 取模分母),须与 shards 中 shard-0..N-1 的数量一致。 */
  private int pooledShardCount = 1;

  /**
   * 多片模式下<b>每片</b>连接池上限。0(默认)= 用 {@code batch.datasource.business} 的池上限(单片默认 20)。
   *
   * <p>多片下每个 worker 会给每片各开一个池,总连接 ≈ 片数 × 本值 × worker 数,易压爆 PG {@code max_connections}。片数多时应显式调小本值(如
   * 5~10),控制每 worker 对每片的连接占用。
   */
  private int shardMaximumPoolSize = 0;

  /** silo 独占覆盖:tenantId → placement key(如 {@code big-corp → silo-big})。 */
  private Map<String, String> siloOverrides = new LinkedHashMap<>();

  /** 各 placement 片的连接凭据(key + url/账密);凭据来源 secrets,禁明文入库。 */
  private List<Shard> shards = new ArrayList<>();

  /** placement 解析来源。 */
  public enum PlacementSource {
    /** hash 池化 + config 里的 siloOverrides;改 placement = 改配置 + 重启。 */
    CONFIG,
    /** 表(batch.business_tenant_placement)覆盖 + hash 回退;迁片/silo 在线维护。 */
    TABLE
  }

  /** 单片连接凭据。{@code key} 必须覆盖 resolver 可能返回的全部 key(含 default {@code shard-0})。 */
  @Data
  public static class Shard {

    /** placement key(如 {@code shard-0} / {@code silo-big}),与 resolver 输出对应。 */
    private String key;

    /** JDBC URL。 */
    private String url;

    /** 登录名。 */
    private String username;

    /** 登录密码(secret manager 注入,禁明文入库)。 */
    private String password;
  }
}
