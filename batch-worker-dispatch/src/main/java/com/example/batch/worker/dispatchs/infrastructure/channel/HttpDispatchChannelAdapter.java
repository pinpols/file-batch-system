package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.security.DnsResolveGuard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.dispatchs.config.HttpDispatchChannelProperties;
import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
  private final BatchSecurityProperties securityProperties;

  public HttpDispatchChannelAdapter(
      HttpDispatchChannelProperties properties, BatchSecurityProperties securityProperties) {
    this.securityProperties = securityProperties;
    this.okHttpClient =
        new OkHttpClient.Builder()
            .connectTimeout(properties.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(properties.getReadTimeoutMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(properties.getWriteTimeoutMillis(), TimeUnit.MILLISECONDS)
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
      // S-2.6: resolve-then-connect — 解析 endpoint 主机名并校验 IP，通过 OkHttp Dns 钉住解析结果
      OkHttpClient client = okHttpClient;
      if (!securityProperties.isBypassMode()) {
        String targetHost = URI.create(endpoint).getHost();
        InetAddress resolved = DnsResolveGuard.resolveAndValidate(targetHost);
        client = okHttpClient.newBuilder().dns(hostname -> List.of(resolved)).build();
      }
      try (Response response = client.newCall(request).execute()) {
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
      return new DispatchResult(
          false, externalRequestId, receiptCode, false, false, ex.getMessage(), endpoint);
    }
  }
}
