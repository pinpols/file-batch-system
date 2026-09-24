package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.SmsProperties;
import io.github.pinpols.batch.console.support.http.ConsoleOutboundTransport;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Twilio 短信 provider（{@link SmsProvider} SPI，{@code batch.console.sms.provider == twilio} 时装配）。
 *
 * <p>对每个手机号调 Twilio Messages API：{@code POST
 * {twilioApiBase}/2010-04-01/Accounts/{AccountSid}/Messages.json}，{@code
 * application/x-www-form-urlencoded} body {@code To=<phone>&From=<twilioFromNumber>&Body=<文案>}（文案由
 * eventType + payloadJson 截断后 URL 编码）。鉴权用 HTTP Basic（{@code base64(AccountSid:AuthToken)}）。
 *
 * <p>accountSid / authToken / fromNumber 任一为空 → {@link WebhookDeliveryResult#failure} 且<b>不走网络</b>。
 * transport 在建连前校验 base host 的全部地址（防 SSRF / rebinding）。多个手机号逐个发，任一非 2xx
 * 即整体 failure（带首个失败状态码）；全部 2xx（Twilio 成功返回 201 + 含 sid 的 JSON）→ {@link WebhookDeliveryResult#ok}。
 *
 * <p>无状态、线程安全（单例 bean，共享出站 transport）；所有失败折叠为 failure 而非抛异常。日志净化：<b>绝不打印 AuthToken /
 * AccountSid / 手机号明文 / Authorization 头</b>，手机号只打数量。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.sms.provider", havingValue = "twilio")
public class TwilioSmsProvider implements SmsProvider {

  /** 短信文案截断上限，避免把整份 payload 灌进短信正文。 */
  private static final int BODY_MAX_CHARS = 480;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final SmsProperties properties;
  private final ObjectMapper objectMapper;
  private final OutboundHttpTransport httpTransport;

  @Autowired
  public TwilioSmsProvider(
      SmsProperties properties,
      ObjectMapper objectMapper,
      @ConsoleOutboundTransport OutboundHttpTransport httpTransport) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpTransport = httpTransport;
  }

  @Override
  public String providerCode() {
    return "twilio";
  }

  @Override
  public WebhookDeliveryResult send(List<String> phoneNumbers, NotificationMessage message) {
    String accountSid = properties.getTwilioAccountSid();
    String authToken = properties.getTwilioAuthToken();
    String fromNumber = properties.getTwilioFromNumber();

    if (accountSid == null || accountSid.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing twilio accountSid");
    }
    if (authToken == null || authToken.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing twilio authToken");
    }
    if (fromNumber == null || fromNumber.isBlank()) {
      return WebhookDeliveryResult.failure(null, "missing twilio fromNumber");
    }
    if (phoneNumbers == null || phoneNumbers.isEmpty()) {
      return WebhookDeliveryResult.failure(null, "missing twilio phoneNumbers");
    }

    String apiBase = resolveApiBase();
    String url = apiBase + "/2010-04-01/Accounts/" + urlEncode(accountSid) + "/Messages.json";
    String authHeader = basicAuthHeader(accountSid, authToken);
    String body = buildBody(message);

    try {
      if (EmptyChecks.isNull(URI.create(apiBase).getHost())) {
        return WebhookDeliveryResult.failure(null, "invalid twilio apiBase");
      }
    } catch (RuntimeException e) {
      return WebhookDeliveryResult.failure(null, "invalid twilio apiBase");
    }

    for (String phone : phoneNumbers) {
      if (phone == null || phone.isBlank()) {
        continue;
      }
      String formBody =
          "To=" + urlEncode(phone) + "&From=" + urlEncode(fromNumber) + "&Body=" + urlEncode(body);
      try {
        TwilioResponse response = postForm(url, authHeader, formBody);
        int status = response.status();
        if (status / 100 != 2) {
          log.warn(
              "[twilio] delivery rejected tenant={} channel={} recipients={} status={}",
              message.tenantId(),
              message.channelCode(),
              phoneNumbers.size(),
              status);
          return WebhookDeliveryResult.failure(status, "twilio http status=" + status);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        log.warn(
            "[twilio] delivery interrupted tenant={} channel={} recipients={}",
            message.tenantId(),
            message.channelCode(),
            phoneNumbers.size());
        return WebhookDeliveryResult.failure(null, ie.getClass().getSimpleName());
      } catch (Exception e) {
        log.warn(
            "[twilio] delivery failed tenant={} channel={} recipients={} ex={}",
            message.tenantId(),
            message.channelCode(),
            phoneNumbers.size(),
            e.getClass().getSimpleName());
        return WebhookDeliveryResult.failure(null, e.getClass().getSimpleName());
      }
    }
    return WebhookDeliveryResult.ok();
  }

  /** 末尾斜杠归一化，避免拼出 {@code //2010-04-01}。 */
  private String resolveApiBase() {
    String base = properties.getTwilioApiBase();
    if (base == null || base.isBlank()) {
      base = "https://api.twilio.com";
    }
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }

  /** HTTP Basic：{@code Basic base64(AccountSid:AuthToken)}。 */
  private static String basicAuthHeader(String accountSid, String authToken) {
    String raw = accountSid + ":" + authToken;
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** 由 eventType + payloadJson 拼成截断后的简洁文案。 */
  private String buildBody(NotificationMessage message) {
    String eventType =
        message.payload() == null ? message.channelCode() : message.payload().eventType();
    String detail = message.payloadJson() == null ? "" : message.payloadJson();
    String summary = (eventType == null ? "" : eventType) + " " + detail;
    summary = summary.trim();
    return summary.length() <= BODY_MAX_CHARS ? summary : summary.substring(0, BODY_MAX_CHARS);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** POST 表单单次响应（HTTP 状态码 + 响应体），便于单测预置覆盖。 */
  protected record TwilioResponse(int status, String body) {}

  /** 抽出便于单测覆盖：以 {@code application/x-www-form-urlencoded} POST 表单到 url，带 Basic 鉴权头，返回状态码 + 响应体。 */
  protected TwilioResponse postForm(String url, String authHeader, String body)
      throws IOException, InterruptedException {
    OutboundHttpResponse response = httpTransport.execute(OutboundHttpRequest.post(
        url,
        Map.of("Authorization", authHeader),
        body,
        "application/x-www-form-urlencoded; charset=utf-8",
        CONNECT_TIMEOUT,
        REQUEST_TIMEOUT,
        OutboundAddressPolicy.GUARDED));
    return new TwilioResponse(response.statusCode(), response.body());
  }
}
