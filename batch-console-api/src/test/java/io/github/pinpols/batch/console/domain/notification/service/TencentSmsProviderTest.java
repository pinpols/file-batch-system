package io.github.pinpols.batch.console.domain.notification.service;

import static io.github.pinpols.batch.testing.TestHttpTransports.failOnRequest;
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
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("腾讯云短信通道: 配置校验, 响应码判定与日志脱敏")
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
  @DisplayName("渠道标识大小写不敏感地识别该通道, 其它渠道与空值不支持")
  void shouldSupportTencentCaseInsensitively() {
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper, failOnRequest());
    assertThat(provider.supports("tencent")).isTrue();
    assertThat(provider.supports("TENCENT")).isTrue();
    assertThat(provider.supports("Tencent")).isTrue();
    assertThat(provider.supports("aliyun")).isFalse();
    assertThat(provider.supports(null)).isFalse();
  }

  @Test
  @DisplayName("缺少应用标识时直接失败, 不发起任何网络请求")
  void shouldFailWithoutNetworkCall_whenSdkAppIdMissing() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result =
        provider.send(PHONE_NUMBERS, message("{\"signName\":\"腾讯云\",\"templateId\":\"1234567\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms sdkAppId");
    assertThat(called.get()).isFalse();
  }

  @Test
  @DisplayName("缺少短信签名时直接失败, 不发起任何网络请求")
  void shouldFailWithoutNetworkCall_whenSignNameMissing() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result = provider.send(
        PHONE_NUMBERS, message("{\"sdkAppId\":\"1400000000\",\"templateId\":\"1234567\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms signName");
    assertThat(called.get()).isFalse();
  }

  @Test
  @DisplayName("缺少模板编号时直接失败, 不发起任何网络请求")
  void shouldFailWithoutNetworkCall_whenTemplateIdMissing() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result =
        provider.send(PHONE_NUMBERS, message("{\"sdkAppId\":\"1400000000\",\"signName\":\"腾讯云\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms templateId");
    assertThat(called.get()).isFalse();
  }

  @Test
  @DisplayName("手机号清单为空时直接失败, 不发起任何网络请求")
  void shouldFailWithoutNetworkCall_whenPhoneNumbersEmpty() {
    AtomicBoolean called = new AtomicBoolean(false);
    TencentSmsProvider provider = providerRecording(called, "{\"Response\":{}}");

    WebhookDeliveryResult result = provider.send(List.of(), message(fullConfig()));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms phoneNumbers");
    assertThat(called.get()).isFalse();
  }

  @Test
  @DisplayName("发送状态码为成功时判定成功, 请求体与鉴权头按规范构造且签名稳定")
  void shouldSucceed_whenStatusSetCodeOk() {
    AtomicReference<String> sentUrl = new AtomicReference<>();
    AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
    AtomicReference<String> sentBody = new AtomicReference<>();
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper, failOnRequest()) {
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
    assertThat(sentHeaders.get()).containsEntry("X-TC-Action", "SendSms");
    assertThat(sentHeaders.get()).containsEntry("X-TC-Version", "2021-01-11");
    assertThat(sentHeaders.get()).containsEntry("X-TC-Region", "ap-guangzhou");
    assertThat(sentHeaders.get().get("Authorization"))
        .startsWith("TC3-HMAC-SHA256 Credential=sid-test/")
        .contains("/sms/tc3_request")
        .contains("SignedHeaders=content-type;host")
        .contains("Signature=");
    // 签名确定:固定 timestamp/secret + 同 body → 相同 signature(防漂移)。
    String auth1 = sentHeaders.get().get("Authorization");
    provider.send(PHONE_NUMBERS, message(fullConfig()));
    assertThat(sentHeaders.get()).containsEntry("Authorization", auth1);
  }

  @Test
  @DisplayName("发送状态码非成功时判定失败, 错误摘要带业务码")
  void shouldFail_whenStatusSetCodeNotOk() {
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper, failOnRequest()) {
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
  @DisplayName("响应体带错误对象时判定失败, 错误摘要带错误码")
  void shouldFail_whenResponseCarriesError() {
    TencentSmsProvider provider =
        new TencentSmsProvider(properties(), objectMapper, failOnRequest()) {
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
  @DisplayName("任何一条日志都不出现手机号明文, 只留脱敏信息")
  void shouldMaskPhoneNumberInLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(TencentSmsProvider.class);
    ListAppender appender = new ListAppender();
    appender.start();
    logger.addAppender(appender);
    try {
      TencentSmsProvider provider =
          new TencentSmsProvider(properties(), objectMapper, failOnRequest()) {
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
    return new TencentSmsProvider(properties(), objectMapper, failOnRequest()) {
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
