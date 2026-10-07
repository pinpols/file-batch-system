package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.orchestrator.auth.ApiKeyEntity;
import io.github.pinpols.batch.orchestrator.auth.ApiKeyVerifier;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("内部鉴权过滤器,验证接口密钥与内部密钥两条凭据通道的放行与拒绝分流,并核验租户上下文传递,旁路模式及非内部路径直通行为")
class InternalAuthFilterTest {

  private BatchSecurityProperties props;
  private ApiKeyVerifier verifier;
  private InternalAuthFilter filter;

  @BeforeEach
  void setUp() {
    BatchMdc.clear();
    props = new BatchSecurityProperties();
    props.setInternalSecret("super-secret");
    props.setBypassMode(false);
    verifier = mock(ApiKeyVerifier.class);
    filter = new InternalAuthFilter(props, verifier);
  }

  // ─── path 1: API key ──────────────────────────────────────────────────────

  @Test
  @DisplayName("接口密钥校验通过时放行,链内租户取密钥归属租户,外层日志上下文不被污染")
  void shouldResolveTenantFromApiKeyAndPreserveOuterMdc_whenKeyHit() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/workers/heartbeat");
    req.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "raw-key");
    req.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tx");
    // /internal/workers/* + /internal/tasks/* 现在走 verifyWithScope(worker.execute)
    when(verifier.verifyWithScope("raw-key", "tx", "worker.execute"))
        .thenReturn(
            Optional.of(new ApiKeyEntity(1L, "tx", "n", "*", true, null, "h", "s", "pbkdf2")));

    MockHttpServletResponse resp = new MockHttpServletResponse();
    BatchMdc.put(StructuredLogField.TENANT_ID, "outer-tenant");
    AtomicReference<String> tenantInChain = new AtomicReference<>();
    FilterChain chain = (request, response) ->
        tenantInChain.set(BatchMdc.snapshot().get(StructuredLogField.TENANT_ID));
    filter.doFilterInternal(req, resp, chain);

    assertThat(tenantInChain).hasValue("tx");
    assertThat(BatchMdc.snapshot()).containsEntry(StructuredLogField.TENANT_ID, "outer-tenant");
    assertThat(req.getAttribute(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID)).isEqualTo("tx");
    assertThat(req.getAttribute(InternalAuthFilter.ATTR_API_KEY_RECORD)).isNotNull();
  }

  @Test
  @DisplayName("接口密钥校验失败时返回 401,且同时携带内部密钥也不回退放行")
  void shouldReturn401WithoutSecretFallback_whenApiKeyNotVerified() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "raw-key");
    req.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tx");
    req.addHeader(CommonConstants.INTERNAL_SECRET_HEADER, "super-secret"); // 即使有 secret 也不 fallback
    when(verifier.verifyWithScope(any(), any(), anyString())).thenReturn(Optional.empty());

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("携带接口密钥但缺少租户标识时返回 401")
  void shouldReturn401_whenTenantHeaderMissingWithApiKey() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/workers/heartbeat");
    req.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "raw-key");
    when(verifier.verifyWithScope("raw-key", null, "worker.execute")).thenReturn(Optional.empty());

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("接口密钥访问非执行类内部端点时直接返回 401,且不触达密钥校验与后续链路")
  void shouldReturn401_whenApiKeyCallsNonWorkerInternalEndpoint() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/instances/launch");
    req.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "raw-key");
    req.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tx");

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(verifier, never()).verifyWithScope(any(), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("只读密钥访问查询类内部端点时放行,并写入解析后的租户标识")
  void shouldPass_whenReadOnlyKeyHitsGetEndpoint() throws Exception {
    // GET 读端点用 verifyWithAnyScope(read, execute);只读 key 应放行
    MockHttpServletRequest req =
        new MockHttpServletRequest("GET", "/internal/workers/W1/claimed-tasks");
    req.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "raw-key");
    req.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tx");
    when(verifier.verifyWithAnyScope("raw-key", "tx", "worker.read", "worker.execute"))
        .thenReturn(Optional.of(
            new ApiKeyEntity(1L, "tx", "n", "worker.read", true, null, "h", "s", "pbkdf2")));

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
    assertThat(req.getAttribute(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID)).isEqualTo("tx");
  }

  @Test
  @DisplayName("只读密钥访问变更类内部端点时返回 401,且不进入后续链路")
  void shouldReturn401_whenReadOnlyKeyHitsMutationEndpoint() throws Exception {
    // POST 写端点仍走 verifyWithScope(worker.execute);只读 key 不满足 → 401
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "raw-key");
    req.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tx");
    when(verifier.verifyWithScope("raw-key", "tx", "worker.execute")).thenReturn(Optional.empty());

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  // ─── path 2: legacy secret ────────────────────────────────────────────────

  @Test
  @DisplayName("内部密钥匹配时放行,且不触发接口密钥校验")
  void shouldPassWithoutApiKeyVerification_whenLegacySecretMatches() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader(CommonConstants.INTERNAL_SECRET_HEADER, "super-secret");

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(verifier, never()).verify(anyString(), anyString());
  }

  @Test
  @DisplayName("内部密钥不匹配时返回 401")
  void shouldReturn401_whenLegacySecretMismatches() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader(CommonConstants.INTERNAL_SECRET_HEADER, "wrong");

    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("两类凭据都不携带时返回 401,且不进入后续链路")
  void shouldReturn401_whenNeitherCredentialPresent() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    assertThat(resp.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  // ─── 共通行为 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("旁路模式开启时无需任何请求头即可放行")
  void shouldPassWithoutCredential_whenBypassModeEnabled() throws Exception {
    props.setBypassMode(true);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  @DisplayName("非内部接口路径直接放行,不做凭据校验")
  void shouldPassUntouched_whenUriIsNotInternal() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/console/jobs");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  @DisplayName("未注入接口密钥校验器时,内部密钥凭据仍可放行以兼容旧部署")
  void shouldPassWithLegacySecret_whenApiKeyVerifierAbsent() throws Exception {
    InternalAuthFilter f = new InternalAuthFilter(props, null);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/1/claim");
    req.addHeader(CommonConstants.INTERNAL_SECRET_HEADER, "super-secret");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    f.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }
}
