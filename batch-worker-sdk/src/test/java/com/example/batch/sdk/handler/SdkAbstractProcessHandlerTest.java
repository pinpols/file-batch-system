package com.example.batch.sdk.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-036 Process 模板 {@link SdkAbstractProcessHandler} 单测(经基类 execute 模板序驱动)。 */
class SdkAbstractProcessHandlerTest {

  private static final SdkTaskContext CTX =
      new SdkTaskContext("tx", "job", "ti", 1L, "w-1", Map.of(), Map.of());

  /** 记录每次 upsert 收到的 batch(深拷贝,防 buf.clear() 影响),便于断言分批。 */
  private static List<Integer> ints(int n) {
    List<Integer> rows = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      rows.add(i);
    }
    return rows;
  }

  /** 可配置匿名子类:transform 由 lambda 决定(返回 null = skip),记录每次 upsert 的批。 */
  private static SdkAbstractProcessHandler<Integer, String> handler(
      List<Integer> input,
      int batchSize,
      BiFunction<SdkTaskContext, Integer, String> transform,
      List<List<String>> upsertBatches) {
    return new SdkAbstractProcessHandler<>() {
      @Override
      public String taskType() {
        return "test_process";
      }

      @Override
      protected Iterator<Integer> selectInput(SdkTaskContext ctx) {
        return input.iterator();
      }

      @Override
      protected String transform(SdkTaskContext ctx, Integer in) {
        return transform.apply(ctx, in);
      }

      @Override
      protected void upsert(SdkTaskContext ctx, List<String> batch) {
        upsertBatches.add(new ArrayList<>(batch));
      }

      @Override
      protected int batchSize() {
        return batchSize;
      }
    };
  }

  @Test
  @DisplayName("100 行全成功,batchSize=40 → upsert 3 次(40+40+20),success=100,total=100")
  void shouldBatchUpsert_when100RowsBatch40() {
    // arrange
    List<List<String>> batches = new ArrayList<>();
    var h = handler(ints(100), 40, (ctx, i) -> "o" + i, batches);

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isTrue();
    assertThat(batches).hasSize(3);
    assertThat(batches.get(0)).hasSize(40);
    assertThat(batches.get(1)).hasSize(40);
    assertThat(batches.get(2)).hasSize(20);
    assertThat(r.output()).containsEntry("success", 100L).containsEntry("total", 100L);
    assertThat(r.message()).isEqualTo("processed 100 rows");
  }

  @Test
  @DisplayName("transform 部分返回 null → 那些行 skipped,success=非null数,total 含 skipped")
  void shouldSkipNullRows_whenTransformReturnsNull() {
    // arrange — 偶数转换、奇数 skip,共 50 偶 50 奇
    List<List<String>> batches = new ArrayList<>();
    var h = handler(ints(100), 40, (ctx, i) -> i % 2 == 0 ? "o" + i : null, batches);

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("success", 50L).containsEntry("skipped", 50L);
    assertThat(r.output()).containsEntry("total", 100L);
    // 50 个写出行,batchSize=40 → 40 + 10
    int written = batches.stream().mapToInt(List::size).sum();
    assertThat(written).isEqualTo(50);
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).hasSize(40);
    assertThat(batches.get(1)).hasSize(10);
  }

  @Test
  @DisplayName("0 行输入 → upsert 不调,success=0")
  void shouldNotUpsert_whenNoInput() {
    // arrange
    List<List<String>> batches = new ArrayList<>();
    var h = handler(List.of(), 40, (ctx, i) -> "o" + i, batches);

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isTrue();
    assertThat(batches).isEmpty();
    assertThat(r.output()).containsEntry("success", 0L).containsEntry("total", 0L);
  }

  @Test
  @DisplayName("整除 batchSize(80 行 / 40)→ upsert 2 次,无空尾 flush")
  void shouldNotFlushEmptyTail_whenRowsAreMultipleOfBatchSize() {
    // arrange
    List<List<String>> batches = new ArrayList<>();
    var h = handler(ints(80), 40, (ctx, i) -> "o" + i, batches);

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isTrue();
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).hasSize(40);
    assertThat(batches.get(1)).hasSize(40);
  }

  @Test
  @DisplayName("selectInput 抛异常 → fail,异常透传")
  void shouldFail_whenSelectInputThrows() {
    // arrange
    var h =
        new SdkAbstractProcessHandler<Integer, String>() {
          @Override
          public String taskType() {
            return "test_process";
          }

          @Override
          protected Iterator<Integer> selectInput(SdkTaskContext ctx) throws Exception {
            throw new IllegalStateException("select boom");
          }

          @Override
          protected String transform(SdkTaskContext ctx, Integer in) {
            return "o" + in;
          }

          @Override
          protected void upsert(SdkTaskContext ctx, List<String> batch) {}
        };

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("select boom");
    assertThat(r.error()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("transform 抛异常 → fail")
  void shouldFail_whenTransformThrows() {
    // arrange — 第 3 行抛
    List<List<String>> batches = new ArrayList<>();
    var h =
        handler(
            ints(10),
            40,
            (ctx, i) -> {
              if (i == 3) {
                throw new RuntimeException("transform boom");
              }
              return "o" + i;
            },
            batches);

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("transform boom");
    assertThat(r.error()).isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("upsert 抛异常 → fail")
  void shouldFail_whenUpsertThrows() {
    // arrange — batchSize 触发首批 upsert 即抛
    var h =
        new SdkAbstractProcessHandler<Integer, String>() {
          @Override
          public String taskType() {
            return "test_process";
          }

          @Override
          protected Iterator<Integer> selectInput(SdkTaskContext ctx) {
            return ints(50).iterator();
          }

          @Override
          protected String transform(SdkTaskContext ctx, Integer in) {
            return "o" + in;
          }

          @Override
          protected void upsert(SdkTaskContext ctx, List<String> batch) throws Exception {
            throw new IllegalArgumentException("upsert boom");
          }

          @Override
          protected int batchSize() {
            return 40;
          }
        };

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("upsert boom");
    assertThat(r.error()).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("自定义 batchSize() 生效 — 10 行 / batchSize=3 → upsert 4 次(3+3+3+1)")
  void shouldHonorCustomBatchSize() {
    // arrange
    List<List<String>> batches = new ArrayList<>();
    var h = handler(ints(10), 3, (ctx, i) -> "o" + i, batches);

    // act
    SdkTaskResult r = h.execute(CTX);

    // assert
    assertThat(r.success()).isTrue();
    assertThat(batches).hasSize(4);
    assertThat(batches.get(0)).hasSize(3);
    assertThat(batches.get(1)).hasSize(3);
    assertThat(batches.get(2)).hasSize(3);
    assertThat(batches.get(3)).hasSize(1);
    assertThat(r.output()).containsEntry("success", 10L);
  }

  @Test
  @DisplayName("不覆盖 batchSize() 时默认为 500")
  void shouldDefaultBatchSizeTo500() {
    // arrange — 不覆盖 batchSize(),用基类默认
    var h =
        new SdkAbstractProcessHandler<Integer, String>() {
          @Override
          public String taskType() {
            return "test_process";
          }

          @Override
          protected Iterator<Integer> selectInput(SdkTaskContext ctx) {
            return List.<Integer>of().iterator();
          }

          @Override
          protected String transform(SdkTaskContext ctx, Integer in) {
            return "o" + in;
          }

          @Override
          protected void upsert(SdkTaskContext ctx, List<String> batch) {}
        };

    // assert
    assertThat(h.batchSize()).isEqualTo(500);
  }
}
