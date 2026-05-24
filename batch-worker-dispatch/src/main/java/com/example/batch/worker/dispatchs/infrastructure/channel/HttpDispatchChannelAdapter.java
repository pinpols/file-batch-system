package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.dispatchs.config.HttpDispatchChannelProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/** HTTP/API 渠道分发适配器，支持 API 和 API_PUSH 类型渠道。 */
@Component
public class HttpDispatchChannelAdapter implements DispatchChannelAdapter {

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final OkHttpClient okHttpClient;

  public HttpDispatchChannelAdapter(
      HttpDispatchChannelProperties properties, BatchSecurityProperties securityProperties) {
    // 一次性把 OkHttpClient 构造好（含 callTimeout 兜底 + 自定义 Dns）并复用：
    // 1) 每次 dispatch 调用 newBuilder().dns(...).build() 会让 connection / dispatcher /
    //    thread pool 被重建，复用价值归零，高并发下还会泄漏线程；
    // 2) 父 Client 缺 callTimeout 时，connect/read/write 各自不超时 ≠ 总时长不超时——
    //    比如 read 拉长导致总时长无界，必须显式 callTimeout 收口。
    Dns guardedDns =
        new Dns() {
          @Override
          public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            if (securityProperties.isBypassMode()) {
              return SYSTEM.lookup(hostname);
            }
            // S-2.6: resolve-then-connect — 解析 + IP 安全校验合并在 Dns 接口实现里，
            // 由 OkHttp 在真正建连前回调，省去每请求重建 Client 的开销。
            return List.of(DnsResolveGuard.resolveAndValidate(hostname));
          }
        };
    this.okHttpClient =
        new OkHttpClient.Builder()
            .connectTimeout(properties.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(properties.getReadTimeoutMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(properties.getWriteTimeoutMillis(), TimeUnit.MILLISECONDS)
            .callTimeout(properties.getCallTimeoutMillis(), TimeUnit.MILLISECONDS)
            .dns(guardedDns)
            .build();
  }

  @Override
  public boolean supports(String channelType) {
    if (channelType == null) {
      return false;
    }
    String u = channelType.toUpperCase(Locale.ROOT);
    return "API".equals(u) || "API_PUSH".equals(u);
  }

  @Override
  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();
    String endpoint =
        channelConfig.get("target_endpoint") == null
            ? null
            : String.valueOf(channelConfig.get("target_endpoint"));
    if (endpoint == null || endpoint.isBlank()) {
      return new DispatchResult(false, null, null, false, false, "target endpoint missing", null);
    }
    String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
    String externalRequestId =
        command.payload().externalRequestId() != null
                && !command.payload().externalRequestId().isBlank()
            ? command.payload().externalRequestId()
            : UUID.randomUUID().toString();
    String receiptCode =
        command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()
            ? command.payload().receiptCode()
            : "R-" + externalRequestId;
    boolean acknowledged =
        "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
    boolean pending =
        "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);

    Map<String, Object> requestPayload = new LinkedHashMap<>();
    requestPayload.put("tenantId", command.tenantId());
    requestPayload.put("traceId", command.traceId());
    requestPayload.put("externalRequestId", externalRequestId);
    requestPayload.put("fileRecord", command.fileRecord());
    requestPayload.put("dispatchPayload", command.payload());

    Request.Builder builder =
        new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(JsonUtils.toJson(requestPayload), JSON));
    String ct = String.valueOf(channelConfig.getOrDefault("channel_type", ""));
    if ("API_PUSH".equalsIgnoreCase(ct)) {
      Object apiKey = channelConfig.get("api_push_api_key");
      if (apiKey != null && !String.valueOf(apiKey).isBlank()) {
        builder.addHeader("X-Api-Key", String.valueOf(apiKey).trim());
      }
      Object authorization = channelConfig.get("authorization");
      if (authorization != null && !String.valueOf(authorization).isBlank()) {
        builder.addHeader("Authorization", String.valueOf(authorization).trim());
      }
    }
    Request request = builder.build();
    try {
      // S-2.6: DNS 解析 + IP 校验在构造期注入的 Dns 实现里完成，这里直接复用单例 Client
      try (Response response = okHttpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          return new DispatchResult(
              false,
              externalRequestId,
              receiptCode,
              false,
              false,
              "dispatch api responded " + response.code(),
              null);
        }
        return new DispatchResult(
            true,
            externalRequestId,
            receiptCode,
            acknowledged,
            pending,
            "dispatched via http adapter",
            endpoint);
      }
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(HttpDispatchChannelAdapter.class, "catch:Exception", ex);

      return new DispatchResult(
          false, externalRequestId, receiptCode, false, false, ex.getMessage(), endpoint);
    }
  }
}
