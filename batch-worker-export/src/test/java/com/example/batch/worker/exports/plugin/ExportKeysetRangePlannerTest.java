package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.plugin.ExportDataContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

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
  void resolve_active_callsMinMaxOnce_andCaches() {
    ExportDataContext ctx = context(1, 4, true);
    AtomicInteger calls = new AtomicInteger();
    Supplier<BigDecimal[]> supplier =
        () -> {
          calls.incrementAndGet();
          return new BigDecimal[] {new BigDecimal("0"), new BigDecimal("100")};
        };

    ExportKeysetRange first = planner.resolve(ctx, supplier);
    ExportKeysetRange second = planner.resolve(ctx, supplier);

    assertThat(calls.get()).isEqualTo(1);
    assertThat(first.active()).isTrue();
    assertThat(first.hiN()).isEqualByComparingTo("25");
    assertThat(second).isSameAs(first);
  }

  @Test
  void resolve_notOptedIn_inactive() {
    ExportDataContext ctx = context(1, 4, false);
    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));
    assertThat(r.active()).isFalse();
  }

  @Test
  void resolve_optedInFromQueryParamSchema_active() {
    Map<String, Object> tc = Map.of("query_param_schema", Map.of("partition_keyset_range", true));
    ExportDataContext ctx =
        new ExportDataContext("t1", "job", "batch", "tpl", tc, new LinkedHashMap<>(), 2, 4);

    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));

    assertThat(r.active()).isTrue();
    assertThat(r.loN()).isEqualByComparingTo("25");
    assertThat(r.hiN()).isEqualByComparingTo("50");
  }

  @Test
  void resolve_optedInFromPostgresJsonbNestedSqlTemplate_active() {
    PGobject queryParamSchema = new PGobject();
    Assertions.assertDoesNotThrow(
        () -> {
          queryParamSchema.setType("jsonb");
          queryParamSchema.setValue("{\"sqlTemplateExport\":{\"partitionKeysetRange\":true}}");
        });
    ExportDataContext ctx =
        new ExportDataContext(
            "t1",
            "job",
            "batch",
            "tpl",
            Map.of("query_param_schema", queryParamSchema),
            new LinkedHashMap<>(),
            3,
            4);

    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));

    assertThat(r.active()).isTrue();
    assertThat(r.loN()).isEqualByComparingTo("50");
    assertThat(r.hiN()).isEqualByComparingTo("75");
  }

  @Test
  void resolve_partitionCount1_inactive() {
    ExportDataContext ctx = context(1, 1, true);
    ExportKeysetRange r = planner.resolve(ctx, minMax("0", "100"));
    assertThat(r.active()).isFalse();
  }

  @Test
  void resolve_supplierThrows_inactiveFallback() {
    ExportDataContext ctx = context(1, 4, true);
    ExportKeysetRange r =
        planner.resolve(
            ctx,
            () -> {
              throw new IllegalStateException("boom");
            });
    assertThat(r.active()).isFalse();
  }

  @Test
  void resolve_supplierReturnsNulls_inactive() {
    ExportDataContext ctx = context(1, 4, true);
    ExportKeysetRange r = planner.resolve(ctx, () -> new BigDecimal[] {null, null});
    assertThat(r.active()).isFalse();
  }
}
