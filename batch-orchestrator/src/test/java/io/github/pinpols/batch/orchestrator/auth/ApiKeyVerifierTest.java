package io.github.pinpols.batch.orchestrator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.orchestrator.mapper.auth.ApiKeyAuthMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ApiKeyVerifierTest {

  @Mock private ApiKeyAuthMapper mapper;
  @InjectMocks private ApiKeyVerifier verifier;

  @BeforeEach
  void wireSelf() {
    // @Lazy self 自注入在单测无 Spring 容器时为 null;指向自身,@Async 方法在测试里同步执行。
    ReflectionTestUtils.setField(verifier, "self", verifier);
  }

  // 至少 8 字符 (KEY_PREFIX_LEN)
  private static final String RAW_KEY = "bk_AAAA-secret-token";
  private static final String PREFIX = RAW_KEY.substring(0, ApiKeyVerifier.KEY_PREFIX_LEN);

  private static ApiKeyEntity legacyRow(long id, String tenant, String scopes, String rawKey) {
    String hash = ApiKeyHasher.legacySha256Hex(rawKey);
    return new ApiKeyEntity(id, tenant, "kn", scopes, true, null, hash, null, "sha256");
  }

  private static ApiKeyEntity pbkdf2Row(long id, String tenant, String scopes, String rawKey) {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf(rawKey);
    return new ApiKeyEntity(id, tenant, "kn", scopes, true, null, sh.hash(), sh.salt(), "pbkdf2");
  }

  @Test
  void verifyMatchesPbkdf2RowByPrefixAndConstantTimeCompare() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    Optional<ApiKeyEntity> result = verifier.verify(RAW_KEY, "tx");

    assertThat(result).contains(rec);
    verify(mapper).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  void verifyMatchesLegacySha256RowAndTriggersUpgrade() {
    ApiKeyEntity legacy = legacyRow(42L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(legacy));

    Optional<ApiKeyEntity> result = verifier.verify(RAW_KEY, "tx");

    assertThat(result).contains(legacy);
    // 触发了 upgrade(同步在测试里执行,@Async 无 spring proxy)
    verify(mapper, times(1))
        .upgradeHashIfLegacy(eq(42L), eq(legacy.keyHash()), anyString(), anyString());
  }

  @Test
  void verifyDoesNotUpgradePbkdf2Row() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    verifier.verify(RAW_KEY, "tx");

    verify(mapper, never()).upgradeHashIfLegacy(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void verifyTouchesLastUsedOnHit() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(pbkdf2Row(7L, "tx", "*", RAW_KEY)));

    verifier.verify(RAW_KEY, "tx");

    verify(mapper, times(1)).touchLastUsedAt(eq(7L));
  }

  @Test
  void verifyReturnsEmptyOnNoCandidates() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of());

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    verify(mapper, never()).touchLastUsedAt(anyLong());
  }

  @Test
  void verifyReturnsEmptyOnHashMismatchWithCandidate() {
    // 候选行存在但 hash 是另一 key 的 — 不应放行,也不触发 touch / upgrade
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", "bk_OTHER-secret");
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    verify(mapper, never()).touchLastUsedAt(anyLong());
    verify(mapper, never()).upgradeHashIfLegacy(anyLong(), any(), any(), any());
  }

  @Test
  void verifyRejectsNullOrBlankKey() {
    assertThat(verifier.verify(null, "tx")).isEmpty();
    assertThat(verifier.verify("", "tx")).isEmpty();
    assertThat(verifier.verify("  ", "tx")).isEmpty();
    verify(mapper, never()).findActiveCandidatesByPrefixAndTenant(any(), any());
  }

  @Test
  void verifyRejectsNullOrBlankTenant() {
    assertThat(verifier.verify(RAW_KEY, null)).isEmpty();
    assertThat(verifier.verify(RAW_KEY, "")).isEmpty();
    verify(mapper, never()).findActiveCandidatesByPrefixAndTenant(any(), any());
  }

  @Test
  void verifyRejectsShortKey() {
    // 短于 KEY_PREFIX_LEN(8) 直接拒;防 substring 越界
    assertThat(verifier.verify("abc", "tx")).isEmpty();
    verify(mapper, never()).findActiveCandidatesByPrefixAndTenant(any(), any());
  }

  // ─── ADR-035 scope 校验 ────────────────────────────────────────────────

  @Test
  void verifyWithScopeRequiresScope() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "read.only", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", "worker.execute")).isEmpty();
  }

  @Test
  void verifyWithScopeAcceptsWildcard() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", "worker.execute")).isPresent();
  }

  @Test
  void verifyWithScopeAcceptsExplicitScope() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "read, worker.execute", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", "worker.execute")).isPresent();
  }

  @Test
  void scopesAllowParser() {
    assertThat(ApiKeyVerifier.scopesAllow("*", "anything")).isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("worker.execute,read", "worker.execute")).isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("worker.execute read", "read")).isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("  worker.execute  ", "worker.execute")).isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("read", "worker.execute")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllow("", "anything")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllow(null, "anything")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllow("anything", null)).isTrue();
  }

  @Test
  void touchAsyncSwallowsExceptions() {
    when(mapper.touchLastUsedAt(anyLong())).thenThrow(new RuntimeException("DB down"));
    verifier.touchAsync(7L); // 不抛
  }
}
