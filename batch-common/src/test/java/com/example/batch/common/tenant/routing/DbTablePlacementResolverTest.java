package com.example.batch.common.tenant.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DbTablePlacementResolverTest {

  /** 可控映射 + 加载计数的假 repository。 */
  private static final class FakeRepository implements TenantPlacementRepository {
    private final Map<String, String> mapping = new HashMap<>();
    private final AtomicInteger loadCount = new AtomicInteger();

    @Override
    public Map<String, String> loadAll() {
      loadCount.incrementAndGet();
      return Map.copyOf(mapping);
    }
  }

  private final AtomicLong clock = new AtomicLong(1000L);

  private DbTablePlacementResolver resolver(FakeRepository repo, long ttlMs) {
    // pooledShardCount=2 的 hash 兜底,用于验证未登记租户走 hash
    HashAndSiloPlacementResolver fallback = new HashAndSiloPlacementResolver(2, Map.of());
    return new DbTablePlacementResolver(repo, fallback, ttlMs, clock::get);
  }

  @Test
  @DisplayName("表命中:返回表里的 placement key(覆盖 hash)")
  void tableHitWins() {
    FakeRepository repo = new FakeRepository();
    repo.mapping.put("t-1", "silo-big");
    DbTablePlacementResolver r = resolver(repo, 5000L);

    assertThat(r.resolve("t-1")).isEqualTo("silo-big");
  }

  @Test
  @DisplayName("未登记租户:退回 hash 兜底")
  void missFallsBackToHash() {
    FakeRepository repo = new FakeRepository();
    DbTablePlacementResolver r = resolver(repo, 5000L);

    String key = r.resolve("t-unlisted");
    assertThat(key).isEqualTo(new HashAndSiloPlacementResolver(2, Map.of()).resolve("t-unlisted"));
    assertThat(key).startsWith("shard-");
  }

  @Test
  @DisplayName("空租户:退回兜底(default key)")
  void blankTenantFallsBack() {
    FakeRepository repo = new FakeRepository();
    DbTablePlacementResolver r = resolver(repo, 5000L);

    assertThat(r.resolve("")).isEqualTo(HashAndSiloPlacementResolver.DEFAULT_KEY);
    assertThat(r.resolve(null)).isEqualTo(HashAndSiloPlacementResolver.DEFAULT_KEY);
  }

  @Test
  @DisplayName("TTL 内复用缓存不重载;过期后重载拾取新映射")
  void cacheRespectsTtl() {
    FakeRepository repo = new FakeRepository();
    repo.mapping.put("t-1", "shard-0");
    DbTablePlacementResolver r = resolver(repo, 5000L);

    assertThat(r.resolve("t-1")).isEqualTo("shard-0"); // 首次加载
    assertThat(repo.loadCount.get()).isEqualTo(1);

    clock.addAndGet(4000L); // 仍在 TTL 内
    repo.mapping.put("t-1", "silo-big"); // 表已改但缓存未过期
    assertThat(r.resolve("t-1")).isEqualTo("shard-0");
    assertThat(repo.loadCount.get()).isEqualTo(1);

    clock.addAndGet(2000L); // 累计 6000 > TTL 5000 → 过期重载
    assertThat(r.resolve("t-1")).isEqualTo("silo-big");
    assertThat(repo.loadCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("repository 返回空(表缺失 fail-open):全部退回 hash")
  void emptyMappingFallsBack() {
    FakeRepository repo = new FakeRepository(); // 空映射
    DbTablePlacementResolver r = resolver(repo, 5000L);

    assertThat(r.resolve("t-1")).startsWith("shard-");
  }

  @Test
  @DisplayName("构造参数校验:repository / fallback 不能为空")
  void rejectsNullDeps() {
    HashAndSiloPlacementResolver fallback = new HashAndSiloPlacementResolver(1, Map.of());
    assertThatThrownBy(() -> new DbTablePlacementResolver(null, fallback, 1000L, clock::get))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DbTablePlacementResolver(new FakeRepository(), null, 1000L, clock::get))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
