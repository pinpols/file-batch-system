package io.github.pinpols.batch.console.support.excel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("内存配置包上传会话并发控制")
class InMemoryTenantConfigExcelStoreTest {

  @Test
  @DisplayName("领取配置包时缺少会话或令牌会被拒绝")
  void shouldRejectClaimWhenSessionOrTokensAreMissing() {
    InMemoryTenantConfigExcelStore store = new InMemoryTenantConfigExcelStore();
    String token = store.save(emptySession());
    String previewToken = store.issuePreviewSnapshot(token, 1L).previewToken();

    assertThat(store.claimForApply("missing", previewToken, "owner")).isFalse();
    assertThat(store.claimForApply(token, null, "owner")).isFalse();
    assertThat(store.claimForApply(token, previewToken, null)).isFalse();
  }

  @Test
  @DisplayName("替换配置包会话时缺少前后预览令牌会被拒绝")
  void shouldRejectSessionReplacementWhenPreviewTokensAreMissing() {
    InMemoryTenantConfigExcelStore store = new InMemoryTenantConfigExcelStore();
    String token = store.save(emptySession());
    String previewToken = store.issuePreviewSnapshot(token, 1L).previewToken();

    assertThat(store.replaceSession(token, null, emptySession(), "next", 2L)).isFalse();
    assertThat(store.replaceSession(token, previewToken, emptySession(), null, 2L))
        .isFalse();
    assertThat(store.get(token)).isNotNull();
  }

  @Test
  @DisplayName("并发应用同一会话时仅允许一个请求领取")
  void shouldAllowSingleClaim_whenApplyRequestsRace() throws Exception {
    InMemoryTenantConfigExcelStore store = new InMemoryTenantConfigExcelStore();
    String token = store.save(emptySession());
    String stalePreviewToken = store.issuePreviewSnapshot(token, 6L).previewToken();
    TenantConfigPackageExcelImportStore.PreviewSnapshot currentPreview =
        store.issuePreviewSnapshot(token, 7L);
    String previewToken = currentPreview.previewToken();
    assertThat(currentPreview.configurationRevision()).isEqualTo(7L);
    assertThat(store.claimForApply(token, stalePreviewToken, "apply-1")).isFalse();
    int contenderCount = 16;
    CountDownLatch ready = new CountDownLatch(contenderCount);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(contenderCount)) {
      List<Future<Boolean>> claims = java.util.stream.IntStream.range(0, contenderCount)
          .mapToObj(ignored -> executor.submit(() -> {
            ready.countDown();
            start.await();
            return store.claimForApply(token, previewToken, "apply-1");
          }))
          .toList();
      ready.await();
      start.countDown();

      assertThat(claims.stream().filter(InMemoryTenantConfigExcelStoreTest::claimSucceeded))
          .hasSize(1);
    }

    assertThat(store.isApplyInProgress(token)).isTrue();
    assertThat(store.configurationRevisionForApply(token, "apply-1")).isEqualTo(7L);
    store.releaseApply(token, "apply-1");
    assertThat(store.claimForApply(token, previewToken, "apply-2")).isTrue();
    store.releaseApply(token, "apply-1");
    assertThat(store.isApplyInProgress(token)).isTrue();
    store.completeApply(token, "apply-1");
    assertThat(store.get(token)).isNotNull();
    store.completeApply(token, "apply-2");
    store.remove(token);
    assertThat(store.get(token)).isNull();
  }

  private static boolean claimSucceeded(Future<Boolean> claim) {
    try {
      return claim.get();
    } catch (Exception e) {
      throw new AssertionError("claim task failed", e);
    }
  }

  private static PackageExcelSession emptySession() {
    List<Map<String, String>> emptyRows = List.of();
    return new PackageExcelSession(
        "config.xlsx",
        "tenant-a",
        Instant.now(),
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows,
        emptyRows);
  }
}
