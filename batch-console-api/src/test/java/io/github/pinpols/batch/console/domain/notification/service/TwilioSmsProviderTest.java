package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 单测覆盖 supports / accountSid·authToken·fromNumber 缺失（不走网络）/ 201 成功 / 4xx 失败 / 多号其一失败整体失败 / 请求断言
 * Authorization 为 Basic 且日志不泄 token·手机号明文。
 */
class TwilioSmsProviderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String ACCOUNT_SID = "ACtestsid";
  private static final String AUTH_TOKEN = "secret-token-xyz";
  private static final String FROM_NUMBER = "+15005550006";
  private static final String PLAIN_PHONE = "+13800138000";
  private static final String PLAIN_PHONE_2 = "+13800138001";

  private SmsProperties properties() {
    SmsProperties props = new SmsProperties();
    props.setProvider("twilio");
    props.setTwilioAccountSid(ACCOUNT_SID);
    props.setTwilioAuthToken(AUTH_TOKEN);
    props.setTwilioFromNumber(FROM_NUMBER);
    props.setTwilioApiBase("https://api.twilio.com");
    return props;
  }

  private NotificationMessage message() {
    WebhookEventPayload payload =
        new WebhookEventPayload("tenant-a", "JOB_FAILED", "jobs", "c1", Instant.EPOCH, null);
    return new NotificationMessage(
        "tenant-a", "ch-sms", "SMS", "{}", payload, "{\"jobId\":\"J1\"}");
  }

  @Test
  void supportsIsCaseInsensitive() {
    TwilioSmsProvider provider = new TwilioSmsProvider(properties(), objectMapper);
    assertThat(provider.supports("twilio")).isTrue();
    assertThat(provider.supports("TWILIO")).isTrue();
    assertThat(provider.supports("Twilio")).isTrue();
    assertThat(provider.supports("aliyun")).isFalse();
    assertThat(provider.supports(null)).isFalse();
  }

  @Test
  void missingAccountSidFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    SmsProperties props = properties();
    props.setTwilioAccountSid("");
    TwilioSmsProvider provider = recording(props, called, 201, "{\"sid\":\"SM1\"}");

    WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE), message());

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isNull();
    assertThat(result.errorSummary()).isEqualTo("missing twilio accountSid");
    assertThat(called.get()).isFalse();
  }

  @Test
  void missingAuthTokenFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    SmsProperties props = properties();
    props.setTwilioAuthToken("");
    TwilioSmsProvider provider = recording(props, called, 201, "{\"sid\":\"SM1\"}");

    WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE), message());

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing twilio authToken");
    assertThat(called.get()).isFalse();
  }

  @Test
  void missingFromNumberFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    SmsProperties props = properties();
    props.setTwilioFromNumber("");
    TwilioSmsProvider provider = recording(props, called, 201, "{\"sid\":\"SM1\"}");

    WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE), message());

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing twilio fromNumber");
    assertThat(called.get()).isFalse();
  }

  @Test
  void status201IsSuccessAndUsesBasicAuth() {
    AtomicReference<String> sentUrl = new AtomicReference<>();
    AtomicReference<String> sentAuth = new AtomicReference<>();
    AtomicReference<String> sentBody = new AtomicReference<>();
    TwilioSmsProvider provider =
        new TwilioSmsProvider(properties(), objectMapper) {
          @Override
          protected TwilioResponse postForm(String url, String authHeader, String body) {
            sentUrl.set(url);
            sentAuth.set(authHeader);
            sentBody.set(body);
            return new TwilioResponse(201, "{\"sid\":\"SM123\",\"status\":\"queued\"}");
          }
        };

    WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE), message());

    assertThat(result.success()).isTrue();
    assertThat(sentUrl.get())
        .isEqualTo("https://api.twilio.com/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json");
    // Basic 鉴权 = base64(AccountSid:AuthToken),且头里不含 token 明文。
    assertThat(sentAuth.get()).startsWith("Basic ");
    assertThat(sentAuth.get()).doesNotContain(AUTH_TOKEN);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8));
    assertThat(sentAuth.get()).isEqualTo(expected);
    // form body 含 To / From / Body 且 URL 编码(+ 变 %2B)。
    assertThat(sentBody.get()).contains("To=%2B13800138000");
    assertThat(sentBody.get()).contains("From=%2B15005550006");
    assertThat(sentBody.get()).contains("Body=");
  }

  @Test
  void status4xxFails() {
    TwilioSmsProvider provider =
        new TwilioSmsProvider(properties(), objectMapper) {
          @Override
          protected TwilioResponse postForm(String url, String authHeader, String body) {
            return new TwilioResponse(400, "{\"code\":21211,\"message\":\"invalid To\"}");
          }
        };

    WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE), message());

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(400);
    assertThat(result.errorSummary()).isEqualTo("twilio http status=400");
  }

  @Test
  void multipleNumbersOneFailureFailsWhole() {
    AtomicReference<Integer> calls = new AtomicReference<>(0);
    TwilioSmsProvider provider =
        new TwilioSmsProvider(properties(), objectMapper) {
          @Override
          protected TwilioResponse postForm(String url, String authHeader, String body) {
            int n = calls.get() + 1;
            calls.set(n);
            // 第一个号成功,第二个号 422 失败 → 整体失败,带首个失败状态。
            return n == 1
                ? new TwilioResponse(201, "{\"sid\":\"SM1\"}")
                : new TwilioResponse(422, "{\"code\":21610}");
          }
        };

    WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE, PLAIN_PHONE_2), message());

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(422);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void logsDoNotLeakTokenOrPhone() {
    Logger logger = (Logger) LoggerFactory.getLogger(TwilioSmsProvider.class);
    ListAppender appender = new ListAppender();
    appender.start();
    logger.addAppender(appender);
    try {
      TwilioSmsProvider provider =
          new TwilioSmsProvider(properties(), objectMapper) {
            @Override
            protected TwilioResponse postForm(String url, String authHeader, String body) {
              return new TwilioResponse(401, "{\"code\":20003}");
            }
          };
      WebhookDeliveryResult result = provider.send(List.of(PLAIN_PHONE), message());
      assertThat(result.success()).isFalse();
      assertThat(appender.messages).isNotEmpty();
      for (String msg : appender.messages) {
        assertThat(msg).doesNotContain(AUTH_TOKEN);
        assertThat(msg).doesNotContain(ACCOUNT_SID);
        assertThat(msg).doesNotContain(PLAIN_PHONE);
      }
    } finally {
      logger.detachAppender(appender);
    }
  }

  private TwilioSmsProvider recording(
      SmsProperties props, AtomicBoolean called, int status, String response) {
    return new TwilioSmsProvider(props, objectMapper) {
      @Override
      protected TwilioResponse postForm(String url, String authHeader, String body) {
        called.set(true);
        return new TwilioResponse(status, response);
      }
    };
  }

  /** 捕获 logback 渲染后消息(含 arguments),供净化断言。 */
  private static final class ListAppender extends AppenderBase<ILoggingEvent> {
    private final List<String> messages = new ArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      messages.add(event.getFormattedMessage());
    }
  }
}
