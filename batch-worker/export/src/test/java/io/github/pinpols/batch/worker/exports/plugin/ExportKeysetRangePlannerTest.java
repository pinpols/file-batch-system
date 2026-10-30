package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.pinpols.batch.common.plugin.ExportDataContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

@DisplayName("导出分片规划:多分片统一哈希和保留区间计算")
class ExportKeysetRangePlannerTest {

  private final ExportKeysetRangePlanner planner = new ExportKeysetRangePlanner();

  private ExportDataContext context(int partitionNo, int partitionCount, boolean optIn) {
    Map<String, Object> tc = new LinkedHashMap<>();
    if (optIn) {
      tc.put("partition_keyset_range", true);
    }
    Map<String, Object> snap = new LinkedHashMap<>();
    return new ExportDataContext(
        "t1", "job", "batch", "tpl", tc, snap, partitionNo, partitionCount);
  }

  private Supplier<BigDecimal[]> minMax(String lo, String hi) {
    return () -> new BigDecimal[] {new BigDecimal(lo), new BigDecimal(hi)};
  }

  @Test
  @DisplayName("等宽切 4 片:首片左闭右开,末片含上界,相邻片无缝衔接")
  void equalWidth_4partitions_firstIsHalfOpen_lastIncludesUpper() {
    ExportKeysetRange p1 =
        ExportKeysetRange.equalWidth(new BigDecimal("0"), new BigDecimal("100"), 4, 1);
    assertThat(p1.active()).isTrue();
    assertThat(p1.loN()).isEqualByComparingTo("0");
    assertThat(p1.hiN()).isEqualByComparingTo("25");
    assertThat(p1.includeUpper()).isFalse();

    ExportKeysetRange p4 =
        ExportKeysetRange.equalWidth(new BigDecimal("0"), new BigDecimal("100"), 4, 4);
    assertThat(p4.active()).isTrue();
    assertThat(p4.loN()).isEqualByComparingTo("75");
    assertThat(p4.hiN()).isEqualByComparingTo("100");
    assertThat(p4.includeUpper()).isTrue();
  }

  @Test
  @DisplayName("多分片即使开启区间配置也不探测边界,统一哈希")
  void shouldUseHashWithoutProbing_whenRangeOptionEnabled() {
    ExportDataContext ctx = context(1, 4, true);
    AtomicInteger calls = new AtomicInteger();
    Supplier<BigDecimal[]> supplier = () -> {
      calls.incrementAndGet();
      return new BigDecimal[] {new BigDecimal("0"), new BigDecimal("100")};
    };

    ExportKeysetRange first = planner.resolve(ctx, supplier);
    ExportKeysetRange second = planner.resolve(ctx, supplier);

    assertThat(calls.get()).isZero();
    assertThat(first.active()).isFalse();
    assertThat(first.partitionCount()).isEqualTo(4);
    assertThat(first.partitionNo()).isEqualTo(1);
    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("多分片忽略已存在的区间缓存，避免恢复旧范围策略")
  void shouldIgnoreCachedRange_whenMultiplePartitions() {
    ExportDataContext ctx = context(1, 4, true);
    ctx.exportSnapshot()
        .put(
            ExportKeysetRangePlanner.SNAP_KEY,
            new ExportKeysetRange(true, BigDecimal.ZERO, BigDecimal.TEN, false, 4, 1));

    ExportKeysetRange result = planner.resolve(ctx, () -> {
      throw new AssertionError("hash partitioning must not probe bounds");
    });

    assertThat(result.active()).isFalse();
    assertThat(result.partitionCount()).isEqualTo(4);
    assertThat(result.partitionNo()).isEqualTo(1);
  }

  @Test
  @DisplayName("未显式开启区间切分时判定为不生效,退回哈希分片")
  void resolve_notOptedIn_inactive() {
    ExportDataContext ctx = context(1, 4, false);
    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));
    assertThat(r.active()).isFalse();
  }

  @Test
  @DisplayName("查询参数模板中开启区间配置仍使用哈希")
  void shouldUseHash_whenRangeOptionInQuerySchema() {
    Map<String, Object> tc = Map.of("query_param_schema", Map.of("partition_keyset_range", true));
    ExportDataContext ctx =
        new ExportDataContext("t1", "job", "batch", "tpl", tc, new LinkedHashMap<>(), 2, 4);

    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));

    assertThat(r.active()).isFalse();
    assertThat(r.partitionNo()).isEqualTo(2);
  }

  @Test
  @DisplayName("JSONB 内嵌区间配置也统一使用哈希")
  void shouldUseHash_whenRangeOptionInJsonb() {
    PGobject queryParamSchema = new PGobject();
    assertThatCode(() -> {
          queryParamSchema.setType("jsonb");
          queryParamSchema.setValue("{\"sqlTemplateExport\":{\"partitionKeysetRange\":true}}");
        })
        .doesNotThrowAnyException();
    ExportDataContext ctx = new ExportDataContext(
        "t1",
        "job",
        "batch",
        "tpl",
        Map.of("query_param_schema", queryParamSchema),
        new LinkedHashMap<>(),
        3,
        4);

    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));

    assertThat(r.active()).isFalse();
    assertThat(r.partitionNo()).isEqualTo(3);
  }

  @Test
  @DisplayName("仅 1 个分片时不再启用区间切分,避免无意义拆分")
  void resolve_partitionCount1_inactive() {
    ExportDataContext ctx = context(1, 1, true);
    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));
    assertThat(r.active()).isFalse();
  }

  @Test
  @DisplayName("取边界值抛异常时静默回退为不生效,不向调用方抛出")
  void resolve_supplierThrows_inactiveFallback() {
    ExportDataContext ctx = context(1, 4, true);
    ExportKeysetRange r = planner.resolve(ctx, () -> {
      throw new IllegalStateException("boom");
    });
    assertThat(r.active()).isFalse();
  }

  @Test
  @DisplayName("边界值缺失时判定为不生效")
  void resolve_supplierReturnsNulls_inactive() {
    ExportDataContext ctx = context(1, 4, true);
    ExportKeysetRange r = planner.resolve(ctx, () -> new BigDecimal[] {null, null});
    assertThat(r.active()).isFalse();
  }
}
