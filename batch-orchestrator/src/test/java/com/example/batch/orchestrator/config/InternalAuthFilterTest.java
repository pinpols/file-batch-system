package com.example.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.orchestrator.auth.ApiKeyEntity;
import com.example.batch.orchestrator.auth.ApiKeyVerifier;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalAuthFilterTest {

  private BatchSecurityProperties props;
  private ApiKeyVerifier verifier;
  private InternalAuthFilter filter;

  @BeforeEach
  void setUp() {
    props = new BatchSecurityProperties();
    props.setInternalSecret("super-secret");
    props.setBypassMode(false);
    verifier = mock(ApiKeyVerifier.class);
    filter = new InternalAuthFilter(props, verifier);
  }

  // ─── path 1: API key ──────────────────────────────────────────────────────

  @Test
  void apiKeyHitPasses() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/workers/heartbeat");
    req.addHeader("X-Batch-Api-Key", "raw-key");
    req.addHeader("X-Batch-Tenant-Id", "tx");
    // /internal/workers/* + /internal/tasks/* 现在走 verifyWithScope(worker.execute)
    when(verifier.verifyWithScope("raw-key", "tx", "worker.execute"))
        .thenReturn(
            Optional.of(new ApiKeyEntity(1L, "tx", "n", "*", true, null, "h", "s", "pbkdf2")));

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
    assertThat(req.getAttribute(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID)).isEqualTo("tx");
    assertThat(req.getAttribute(InternalAuthFilter.ATTR_API_KEY_RECORD)).isNotNull();
  }

  @Test
  void apiKeyProvidedButMissReturns401NoSecretFallback() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader("X-Batch-Api-Key", "raw-key");
    req.addHeader("X-Batch-Tenant-Id", "tx");
    req.addHeader("X-Internal-Secret", "super-secret"); // 即使有 secret 也不 fallback
    when(verifier.verifyWithScope(any(), any(), anyString())).thenReturn(Optional.empty());

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void apiKeyWithoutTenantHeaderReturns401() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/workers/heartbeat");
    req.addHeader("X-Batch-Api-Key", "raw-key");
    when(verifier.verifyWithScope("raw-key", null, "worker.execute")).thenReturn(Optional.empty());

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
  }

  @Test
  void apiKeyCannotReachNonWorkerInternalEndpoint() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/instances/launch");
    req.addHeader("X-Batch-Api-Key", "raw-key");
    req.addHeader("X-Batch-Tenant-Id", "tx");

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(verifier, never()).verifyWithScope(any(), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  // ─── path 2: legacy secret ────────────────────────────────────────────────

  @Test
  void legacySecretPasses() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader("X-Internal-Secret", "super-secret");

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(verifier, never()).verify(anyString(), anyString());
  }

  @Test
  void wrongSecretReturns401() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader("X-Internal-Secret", "wrong");

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
  }

  @Test
  void neitherCredentialReturns401() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  // ─── 共通行为 ─────────────────────────────────────────────────────────────

  @Test
  void bypassModePassesWithoutAnyHeader() throws Exception {
    props.setBypassMode(true);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  void nonInternalUriPassesUntouched() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/console/jobs");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  void verifierMayBeNullForBackwardsCompat() throws Exception {
    InternalAuthFilter f = new InternalAuthFilter(props, null);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader("X-Internal-Secret", "super-secret");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    f.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }
}
