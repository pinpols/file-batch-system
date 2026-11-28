package io.github.pinpols.batch.orchestrator.config;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.security.SecretComparator;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.common.web.ServletRequestPaths;
import io.github.pinpols.batch.orchestrator.auth.ApiKeyCandidate;
import io.github.pinpols.batch.orchestrator.auth.ApiKeyVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部接口共享密钥校验过滤器。
 *
 * <p>注册于 {@code /internal/**}。两类 caller 可通过:
 *
 * <ol>
 *   <li>**主项目可信 worker / orchestrator 内部互调** — 通过 {@code X-Internal-Secret} header(单一共享 secret,
 *       legacy)
 *   <li>**ADR-035 租户自托管 worker** — 通过 {@code X-Batch-Api-Key} + {@code X-Batch-Tenant-Id} 双 header
 *       (per-tenant API key,SHA-256 hash 比对 + tenant 校验)
 * </ol>
 *
 * <p>任一通过即放行;两者皆缺/不匹配返 401。{@code batch.security.bypass-mode=true} 时全放行(本地联调)。
 *
 * <p>API key 校验成功后,把 resolved tenantId 写到 request attribute {@value #ATTR_RESOLVED_TENANT_ID} 让下游
 * controller 用(防租户冒充);secret 校验不写(secret 不绑租户)。
 */
@RequiredArgsConstructor
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_SECRET = CommonConstants.INTERNAL_SECRET_HEADER;
    private static final String HEADER_API_KEY = CommonConstants.BATCH_API_KEY_HEADER;
    private static final String HEADER_TENANT = CommonConstants.BATCH_TENANT_ID_HEADER;

    /** Filter 校验通过后写入,controller 通过 {@code request.getAttribute} 取。 */
    public static final String ATTR_RESOLVED_TENANT_ID = "batch.auth.resolvedTenantId";

    /** Filter 校验通过后写入,值为 {@link ApiKeyCandidate};secret 通道写 null。 */
    public static final String ATTR_API_KEY_RECORD = "batch.auth.apiKeyRecord";

    private static final String API_KEY_INTERNAL_PREFIX_TASKS = "/internal/tasks/";
    private static final String API_KEY_INTERNAL_PREFIX_WORKERS = "/internal/workers/";

    /** SDK 生命周期入口：允许 worker.execute（读方法额外接受 worker.read）。 */
    private static final Set<String> WORKER_EXECUTION_PATHS = Set.of("/internal/workers/register");

    private static final Pattern WORKER_EXECUTION_PATH =
            Pattern.compile("^/internal/workers/[^/]+/(heartbeat|deactivate)$");
    private static final Pattern TASK_EXECUTION_PATH =
            Pattern.compile("^/internal/tasks/[^/]+/(claim|non-idempotent-started|report|renew)$");
    private static final Set<String> TASK_EXECUTION_COLLECTION_PATHS =
            Set.of("/internal/tasks/leases/renew-batch", "/internal/tasks/claim-batch", "/internal/tasks/report-batch");

    /** 只读查询：worker.read 或 worker.execute 均可。 */
    private static final Pattern WORKER_READ_PATH = Pattern.compile("^/internal/workers/[^/]+/claimed-tasks$");

    /**
     * 运维变更入口：只接受 worker.ops。执行凭据不能强制下线、接管、排空/恢复或取消任务；
     * 平台内部调用继续走 X-Internal-Secret。
     */
    private static final Pattern OPS_MUTATION_PATH =
            Pattern.compile("^/internal/workers/[^/]+/(status|drain|force-offline|takeover|warmup|resume)$"
                    + "|^/internal/tasks/[^/]+/cancel$");

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid credentials"
                    + " (X-Internal-Secret or X-Batch-Api-Key+X-Batch-Tenant-Id)\"}";

    private final BatchSecurityProperties securityProperties;

    /** P2 新增:租户自托管 worker 的 API key 校验器。 */
    private final ApiKeyVerifier apiKeyVerifier;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = ServletRequestPaths.applicationPath(request);
        if (!isInternalPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (securityProperties.isBypassMode()) {
            chain.doFilter(request, response);
            return;
        }

        // Path 1: API Key (ADR-035 P2 租户自托管 worker)
        String apiKey = request.getHeader(HEADER_API_KEY);
        String tenantHeader = request.getHeader(HEADER_TENANT);
        if (EmptyValues.isNotBlank(apiKey)) {
            // 租户 API-Key 只允许自托管 worker 的执行/查询端点，以及显式 worker.ops scope 的运维变更端点；
            // 其它内部治理接口仍必须走 X-Internal-Secret，避免 per-tenant key 横向触达平台级内部能力。
            Optional<ApiKeyCandidate> rec = resolveApiKeyCandidate(request.getMethod(), path, apiKey, tenantHeader);
            if (rec.isPresent()) {
                String resolvedTenantId = rec.get().tenantId();
                request.setAttribute(ATTR_RESOLVED_TENANT_ID, resolvedTenantId);
                request.setAttribute(ATTR_API_KEY_RECORD, rec.get());
                Map<String, String> previousContext = BatchMdc.snapshot();
                try {
                    // 只把 API-Key 校验后的 tenant 写入可信 MDC 字段。
                    BatchMdc.put(StructuredLogField.TENANT_ID, resolvedTenantId);
                    chain.doFilter(request, response);
                } finally {
                    BatchMdc.restore(previousContext);
                }
                return;
            }
            // API key 提供但校验失败 → 401(不 fallback secret,防 key 泄漏后凭 secret 冒充)
            writeUnauthorized(response);
            return;
        }

        // Path 2: legacy X-Internal-Secret(主项目 worker / orchestrator 内部互调)
        String header = request.getHeader(HEADER_SECRET);
        if (header != null && SecretComparator.constantTimeEquals(securityProperties.getInternalSecret(), header)) {
            chain.doFilter(request, response);
            return;
        }

        writeUnauthorized(response);
    }

    private Optional<ApiKeyCandidate> resolveApiKeyCandidate(
            String method, String path, String apiKey, String tenantHeader) {
        if (isWorkerExecutionUri(path)) {
            // 执行端点:读方法接受 worker.read 或 worker.execute(execute 是 read 超集);
            // 写方法必须 worker.execute。
            return HttpMethod.GET.matches(method)
                    ? apiKeyVerifier.verifyWithAnyScope(
                            apiKey, tenantHeader, ApiKeyVerifier.SCOPE_WORKER_READ, ApiKeyVerifier.SCOPE_WORKER_EXECUTE)
                    : apiKeyVerifier.verifyWithScope(apiKey, tenantHeader, ApiKeyVerifier.SCOPE_WORKER_EXECUTE);
        }
        if (path != null && WORKER_READ_PATH.matcher(path).matches()) {
            return apiKeyVerifier.verifyWithAnyScope(
                    apiKey, tenantHeader, ApiKeyVerifier.SCOPE_WORKER_READ, ApiKeyVerifier.SCOPE_WORKER_EXECUTE);
        }
        if (path != null && OPS_MUTATION_PATH.matcher(path).matches()) {
            return apiKeyVerifier.verifyWithScope(apiKey, tenantHeader, ApiKeyVerifier.SCOPE_WORKER_OPS);
        }
        // 未知 workers/tasks 路径不得由租户 key 触达，未来的新端点也不会被前缀自动放行。
        return Optional.empty();
    }

    private static boolean isWorkerExecutionUri(String path) {
        if (path == null) {
            return false;
        }
        return WORKER_EXECUTION_PATHS.contains(path)
                || WORKER_EXECUTION_PATH.matcher(path).matches()
                || TASK_EXECUTION_PATH.matcher(path).matches()
                || TASK_EXECUTION_COLLECTION_PATHS.contains(path);
    }

    private static boolean isInternalPath(String path) {
        return "/internal".equals(path) || (path != null && path.startsWith("/internal/"));
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
