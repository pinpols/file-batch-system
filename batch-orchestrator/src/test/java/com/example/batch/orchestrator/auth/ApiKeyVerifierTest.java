package com.example.batch.orchestrator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyVerifierTest {

  @Mock private ApiKeyAuthMapper mapper;
  @InjectMocks private ApiKeyVerifier verifier;

  // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
  private static final String RAW_KEY = "hello";
  private static final String EXPECTED_HASH =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  @Test
  void verifyHashesAndQueriesWithTenant() {
    ApiKeyRecord rec = new ApiKeyRecord(1L, "tx", "kn", "*", true, null);
    when(mapper.findActiveByHashAndTenant(EXPECTED_HASH, "tx")).thenReturn(Optional.of(rec));

    Optional<ApiKeyRecord> result = verifier.verify(RAW_KEY, "tx");

    assertThat(result).contains(rec);
    verify(mapper).findActiveByHashAndTenant(EXPECTED_HASH, "tx");
  }

  @Test
  void verifyTouchesLastUsedOnHit() {
    when(mapper.findActiveByHashAndTenant(anyString(), anyString()))
        .thenReturn(Optional.of(new ApiKeyRecord(42L, "tx", "n", "*", true, Instant.MAX)));

    verifier.verify(RAW_KEY, "tx");

    verify(mapper, times(1)).touchLastUsedAt(eq(42L));
  }

  @Test
  void verifyReturnsEmptyOnMiss() {
    when(mapper.findActiveByHashAndTenant(anyString(), anyString())).thenReturn(Optional.empty());

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    verify(mapper, never()).touchLastUsedAt(anyLong());
  }

  @Test
  void verifyRejectsNullOrBlankKey() {
    assertThat(verifier.verify(null, "tx")).isEmpty();
    assertThat(verifier.verify("", "tx")).isEmpty();
    assertThat(verifier.verify("  ", "tx")).isEmpty();
    verify(mapper, never()).findActiveByHashAndTenant(any(), any());
  }

  @Test
  void verifyRejectsNullOrBlankTenant() {
    assertThat(verifier.verify(RAW_KEY, null)).isEmpty();
    assertThat(verifier.verify(RAW_KEY, "")).isEmpty();
    verify(mapper, never()).findActiveByHashAndTenant(any(), any());
  }

  @Test
  void touchAsyncSwallowsExceptions() {
    // touch 失败不应让 verify 异常,因为 hit 已写到 Optional 里(touch 是异步副作用)
    when(mapper.findActiveByHashAndTenant(anyString(), anyString()))
        .thenReturn(Optional.of(new ApiKeyRecord(7L, "tx", "n", "*", true, null)));
    when(mapper.touchLastUsedAt(anyLong())).thenThrow(new RuntimeException("DB down"));

    // touchAsync 同步调用(@Async 在测试里无 Spring proxy,直接进方法)— 仍应被 catch 不抛
    verifier.touchAsync(7L);
    assertThat(verifier.verify(RAW_KEY, "tx")).isPresent(); // 再走一次也不影响
  }
}
