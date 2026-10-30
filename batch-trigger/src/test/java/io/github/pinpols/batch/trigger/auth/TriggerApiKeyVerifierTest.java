package io.github.pinpols.batch.trigger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.trigger.mapper.auth.TriggerApiKeyAuthMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("外部事件 API Key 的租户与显式权限校验")
class TriggerApiKeyVerifierTest {

  private static final String RAW_KEY = "bk_test-event-source-key";
  private static ApiKeyHasher.SaltedHash saltedHash;

  @Mock
  private TriggerApiKeyAuthMapper mapper;

  private TriggerApiKeyVerifier verifier;

  @BeforeAll
  static void createCredentialHash() {
    saltedHash = ApiKeyHasher.hashWithSaltKdf(RAW_KEY);
  }

  @BeforeEach
  void setUp() {
    verifier = new TriggerApiKeyVerifier(mapper, Instant::now);
  }

  @DisplayName("密钥只授权所属租户与显式事件权限")
  @Test
  void shouldAuthenticateKeyOnlyForItsTenantAndExplicitEventScope() {
    when(mapper.findActiveCandidatesByPrefixAndTenant("bk_test-", "tenant-a"))
        .thenReturn(List.of(credential("tenant-a", "billing-source", "trigger.event")));

    Optional<TriggerApiKeyPrincipal> principal = verifier.authenticate(RAW_KEY, "tenant-a");

    assertThat(principal).isPresent();
    assertThat(
            verifier.authorizesExternalEvent(principal.orElseThrow(), "tenant-a", "billing-source"))
        .isTrue();
    assertThat(
            verifier.authorizesExternalEvent(principal.orElseThrow(), "tenant-b", "billing-source"))
        .isFalse();
    assertThat(
            verifier.authorizesExternalEvent(principal.orElseThrow(), "tenant-a", "forged-source"))
        .isFalse();
    verify(mapper).touchLastUsedAt(41L, "tenant-a");
  }

  @DisplayName("事件来源拒绝通配及无关权限")
  @Test
  void shouldRejectWildcardOrUnrelatedScopesForEventSources() {
    TriggerApiKeyPrincipal wildcard = principal("billing-source", "*");
    TriggerApiKeyPrincipal readOnly = principal("billing-source", "worker.read");

    assertThat(verifier.authorizesExternalEvent(wildcard, "tenant-a", "billing-source"))
        .isFalse();
    assertThat(verifier.authorizesExternalEvent(readOnly, "tenant-a", "billing-source"))
        .isFalse();
  }

  @DisplayName("拒绝无效或过期凭证")
  @Test
  void shouldRejectInvalidOrExpiredCredentials() {
    when(mapper.findActiveCandidatesByPrefixAndTenant("bk_test-", "tenant-a"))
        .thenReturn(List.of(credential("tenant-a", "billing-source", "trigger.event")));

    assertThat(verifier.authenticate("bk_test-wrong-key", "tenant-a")).isEmpty();
    assertThat(verifier.authenticate(RAW_KEY, " ")).isEmpty();

    when(mapper.findActiveCandidatesByPrefixAndTenant("bk_test-", "tenant-a"))
        .thenReturn(
            List.of(credential("tenant-a", "billing-source", "trigger.event", Instant.EPOCH)));
    verifier.clearCacheForTests();
    assertThat(verifier.authenticate(RAW_KEY, "tenant-a")).isEmpty();
  }

  private static TriggerApiKeyPrincipal principal(String name, String scopes) {
    return new TriggerApiKeyPrincipal(41L, "tenant-a", name, scopes, null);
  }

  private static TriggerApiKeyCredential credential(String tenantId, String name, String scopes) {
    return credential(tenantId, name, scopes, null);
  }

  private static TriggerApiKeyCredential credential(
      String tenantId, String name, String scopes, Instant expiresAt) {
    return new TriggerApiKeyCredential(
        41L,
        tenantId,
        name,
        scopes,
        true,
        expiresAt,
        saltedHash.hash(),
        saltedHash.salt(),
        ApiKeyHasher.ALGO_PBKDF2);
  }
}
