package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.common.utils.Texts;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

  private final HttpClient httpClient;

  DryRunEndpointProbe() {
    this(HttpClient.newBuilder().connectTimeout(HTTP_PROBE_TIMEOUT).build());
  }

  DryRunEndpointProbe(HttpClient httpClient) {
    this.httpClient = httpClient;
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
        findings.add(
            DryRunFinding.warn(
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
        findings.add(
            DryRunFinding.warn(
                "EXEC_ENDPOINT_BLOCKED",
                SCOPE_EXECUTION,
                key + " endpoint URL has no host; reachability probe skipped",
                url));
        return true;
      }
      if (!endpointEgressAllowed(key, url, host, findings)) {
        return true;
      }
      HttpRequest request =
          HttpRequest.newBuilder(probeUri)
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .timeout(HTTP_PROBE_TIMEOUT)
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status >= 200 && status < 500) {
        findings.add(
            DryRunFinding.pass(
                "EXEC_ENDPOINT_OK", SCOPE_EXECUTION, key + " reachable; HEAD returned " + status));
      } else {
        findings.add(
            DryRunFinding.warn(
                "EXEC_ENDPOINT_5XX", SCOPE_EXECUTION, key + " HEAD returned " + status, url));
      }
      return true;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      findings.add(
          DryRunFinding.warn(
              "EXEC_ENDPOINT_PROBE_INTERRUPTED",
              SCOPE_EXECUTION,
              key + " probe interrupted; remaining endpoint probes skipped",
              url));
      return false;
    } catch (Exception ex) {
      findings.add(
          DryRunFinding.warn(
              "EXEC_ENDPOINT_UNREACHABLE",
              SCOPE_EXECUTION,
              key + " probe failed: " + ex.getMessage(),
              url));
      return true;
    }
  }

  private boolean endpointEgressAllowed(
      String key, String url, String host, List<DryRunFinding> findings) {
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (DnsResolveGuard.isBlocked(address)) {
          findings.add(
              DryRunFinding.warn(
                  "EXEC_ENDPOINT_BLOCKED",
                  SCOPE_EXECUTION,
                  key + " target rejected by egress security policy; reachability probe skipped",
                  url));
          return false;
        }
      }
      return true;
    } catch (UnknownHostException ex) {
      findings.add(
          DryRunFinding.warn(
              "EXEC_ENDPOINT_UNREACHABLE",
              SCOPE_EXECUTION,
              key + " host unresolvable; reachability probe skipped",
              url));
      return false;
    }
  }
}
