package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.security.BlockedAddressException;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.common.utils.Texts;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** L3 HTTP 可达性探测，负责协议校验、SSRF 出口守卫及短超时 HEAD 请求。 */
final class DryRunEndpointProbe {

  private static final String SCOPE_EXECUTION = "execution";
  private static final Duration HTTP_PROBE_TIMEOUT = Duration.ofSeconds(5);
  private static final Set<String> ENDPOINT_PARAM_KEYS =
      Set.of("endpointUrl", "callbackUrl", "channelEndpoint", "dispatchTarget");

  private final OutboundHttpTransport httpTransport;

  DryRunEndpointProbe(OutboundHttpTransport httpTransport) {
    this.httpTransport = httpTransport;
  }

  int probe(Map<String, Object> params, List<DryRunFinding> findings) {
    int probed = 0;
    for (String key : ENDPOINT_PARAM_KEYS) {
      Object raw = params.get(key);
      if (!(raw instanceof String url) || !Texts.hasText(url)) {
        continue;
      }
      probed++;
      String trimmed = url.trim();
      if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        findings.add(DryRunFinding.warn(
            "EXEC_ENDPOINT_NON_HTTP",
            SCOPE_EXECUTION,
            "endpoint not http/https; reachability probe skipped: " + key,
            trimmed));
        continue;
      }
      if (!probeEndpoint(key, trimmed, findings)) {
        break;
      }
    }
    return probed;
  }

  private boolean probeEndpoint(String key, String url, List<DryRunFinding> findings) {
    try {
      URI probeUri = URI.create(url);
      String host = probeUri.getHost();
      if (host == null) {
        findings.add(DryRunFinding.warn(
            "EXEC_ENDPOINT_BLOCKED",
            SCOPE_EXECUTION,
            key + " endpoint URL has no host; reachability probe skipped",
            url));
        return true;
      }
      // Probe 本身必须保持 fail-closed；transport 侧会在真正建连时再次校验同一规则，
      // 避免测试替身或未来其他实现意外绕过 dry-run 的 SSRF 结论。
      DnsResolveGuard.resolveAllAndValidate(host);
      OutboundHttpResponse response = httpTransport.execute(OutboundHttpRequest.head(
          probeUri.toString(),
          Map.of(),
          HTTP_PROBE_TIMEOUT,
          HTTP_PROBE_TIMEOUT,
          OutboundAddressPolicy.GUARDED));
      int status = response.statusCode();
      if (status >= 200 && status < 500) {
        findings.add(DryRunFinding.pass(
            "EXEC_ENDPOINT_OK", SCOPE_EXECUTION, key + " reachable; HEAD returned " + status));
      } else {
        findings.add(DryRunFinding.warn(
            "EXEC_ENDPOINT_5XX", SCOPE_EXECUTION, key + " HEAD returned " + status, url));
      }
      return true;
    } catch (BlockedAddressException ex) {
      findings.add(DryRunFinding.warn(
          "EXEC_ENDPOINT_BLOCKED",
          SCOPE_EXECUTION,
          key + " target rejected by egress security policy; reachability probe skipped",
          url));
      return true;
    } catch (Exception ex) {
      findings.add(DryRunFinding.warn(
          "EXEC_ENDPOINT_UNREACHABLE",
          SCOPE_EXECUTION,
          key + " probe failed: " + ex.getMessage(),
          url));
      return true;
    }
  }
}
