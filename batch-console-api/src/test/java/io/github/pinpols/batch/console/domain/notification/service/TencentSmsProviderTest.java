package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.SmsProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 单测覆盖 supports("tencent") / 缺 sdkAppId / 缺 signName / 缺 templateId（不走网络）/ Code==Ok / Code!=Ok /
 * Response.Error / 日志净化（不含手机号明文）。
 *
 * <p>手机号由入参 {@code phoneNumbers} 提供（不从 config_json 解析）。
 *
 * <p>注：TC3 端到端签名无官方 golden 向量，本测仅验结构 / 确定性 + 分支；真实签名正确性需对接真 API 联调验签。
 */
class TencentSmsProviderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String PLAIN_PHONE = "+8613800138000";
  private static final List<String> PHONE_NUMBERS = List.of(PLAIN_PHONE);

  private SmsProperties properties() {
    SmsProperties props = new SmsProperties();
    props.setTencentSecretId("sid-test");
    props.setTencentSecretKey("skey-test");
    props.setTencentEndpoint("sms.tencentcloudapi.com");
    props.setTencentRegion("ap-guangzhou");
    return props;
  }

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("tenant-a", "JOB_FAILED", "jobs", "c1", Instant.EPOCH, null);
    return new NotificationMessage(
        "tenant-a", "ch-sms", "SMS", configJson, payload, "{\"jobId\":\"J1\"}");
  }

  private String fullConfig() {
    return "{\"sdkAppId\":\"1400000000\",\"signName\":\"腾讯云\",\"templateId\":\"1234567\"}";
  }

  @Test
  void supportsIsCaseInsensitive() {
    TencentSmsProvider provider = new TencentSmsProvider(properties(), objectMapper);
    assertThat(provider.supports("tencent")).isTrue();
    assertThat(provider.supports("TENCENT")).isTrue();
    assertThat(provider.supports("Tencent")).isTrue();
    assertThat(provider.supports("aliyun")).isFalse();
    assertThat(provider.supports(null)).isFalse();
  }

  @Test
  void missingSdkAppIdFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result =
        provider.send(PHONE_NUMBERS, message("{\"signName\":\"腾讯云\",\"templateId\":\"1234567\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms sdkAppId");
    assertThat(called.get()).isFalse();
  }

  @Test
  void missingSignNameFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result =
        provider.send(
            PHONE_NUMBERS, message("{\"sdkAppId\":\"1400000000\",\"templateId\":\"1234567\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms signName");
    assertThat(called.get()).isFalse();
  }

  @Test
  void missingTemplateIdFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result =
        provider.send(PHONE_NUMBERS, message("{\"sdkAppId\":\"1400000000\",\"signName\":\"腾讯云\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms templateId");
    assertThat(called.get()).isFalse();
  }

  @Test
  void emptyPhoneNumbersFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result = provider.send(List.of(), message(fullConfig()));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms phoneNumbers");
    assertThat(called.get()).isFalse();
  }

  @Test
  void codeOkIsSuccess() {
    AtomicReference<String> sentUrl = new AtomicReference<>();
    AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
    AtomicReference<String> sentBody = new AtomicReference<>();
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper) {
          @Override
          protected long epochSeconds() {
            return 1750000000L;
          }

          @Override
          protected String postJson(String url, Map<String, String> headers, String body) {
            sentUrl.set(url);
            sentHeaders.set(headers);
            sentBody.set(body);
            return "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"Ok\",\"PhoneNumber\":\"x\"}],"
                + "\"RequestId\":\"r1\"}}";
          }
        };

    WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message(fullConfig()));

    assertThat(result.success()).isTrue();
    // 结构断言:body 含必填字段 + 手机号集合;Authorization 头按 TC3 构造。
    assertThat(sentUrl.get()).isEqualTo("https://sms.tencentcloudapi.com/");
    assertThat(sentBody.get()).contains("\"SmsSdkAppId\":\"1400000000\"");
    assertThat(sentBody.get()).contains("\"SignName\":\"腾讯云\"");
    assertThat(sentBody.get()).contains("\"TemplateId\":\"1234567\"");
    assertThat(sentBody.get()).contains("\"PhoneNumberSet\":[\"+8613800138000\"]");
    assertThat(sentBody.get()).contains("\"TemplateParamSet\":[\"JOB_FAILED\"]");
    assertThat(sentHeaders.get().get("X-TC-Action")).isEqualTo("SendSms");
    assertThat(sentHeaders.get().get("X-TC-Version")).isEqualTo("2021-01-11");
    assertThat(sentHeaders.get().get("X-TC-Region")).isEqualTo("ap-guangzhou");
    assertThat(sentHeaders.get().get("Authorization"))
        .startsWith("TC3-HMAC-SHA256 Credential=sid-test/")
        .contains("/sms/tc3_request")
        .contains("SignedHeaders=content-type;host")
        .contains("Signature=");
    // 签名确定:固定 timestamp/secret + 同 body → 相同 signature(防漂移)。
    String auth1 = sentHeaders.get().get("Authorization");
    provider.send(PHONE_NUMBERS, message(fullConfig()));
    assertThat(sentHeaders.get().get("Authorization")).isEqualTo(auth1);
  }

  @Test
  void nonOkCodeFails() {
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper) {
          @Override
          protected String postJson(String url, Map<String, String> headers, String body) {
            return "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"LimitExceeded.PhoneNumberDailyLimit\","
                       + "\"Message\":\"limit\"}],\"RequestId\":\"r1\"}}";
          }
        };

    WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message(fullConfig()));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("sms code=LimitExceeded.PhoneNumberDailyLimit");
  }

  @Test
  void responseErrorFails() {
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper) {
          @Override
          protected String postJson(String url, Map<String, String> headers, String body) {
            return "{\"Response\":{\"Error\":{\"Code\":\"AuthFailure.SignatureFailure\","
                + "\"Message\":\"sig\"},\"RequestId\":\"r1\"}}";
          }
        };

    WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message(fullConfig()));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("sms error=AuthFailure.SignatureFailure");
  }

  @Test
  void logsDoNotContainPlainPhoneNumber() {
    Logger logger = (Logger) LoggerFactory.getLogger(TencentSmsProvider.class);
    ListAppender appender = new ListAppender();
    appender.start();
    logger.addAppender(appender);
    try {
      TencentSmsProvider provider =
          new TencentSmsProvider(properties(), objectMapper) {
            @Override
            protected String postJson(String url, Map<String, String> headers, String body) {
              return "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"FailedOperation.PhoneNumberInBlacklist\"}],"
                         + "\"RequestId\":\"r1\"}}";
            }
          };
      WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message(fullConfig()));
      assertThat(result.success()).isFalse();
      assertThat(appender.messages).isNotEmpty();
      for (String msg : appender.messages) {
        assertThat(msg).doesNotContain(PLAIN_PHONE);
      }
    } finally {
      logger.detachAppender(appender);
    }
  }

  private TencentSmsProvider providerRecording(AtomicBoolean called, String response) {
    return new TencentSmsProvider(properties(), objectMapper) {
      @Override
      protected String postJson(String url, Map<String, String> headers, String body) {
        called.set(true);
        return response;
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
