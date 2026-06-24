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
 * 单测覆盖 supports("aliyun") / 缺 signName / 缺 templateCode（不走网络）/ Code==OK / Code!=OK / 日志净化（不含手机号明文）。
 *
 * <p>手机号由入参 {@code phoneNumbers} 提供（不再从 config_json 解析）。
 *
 * <p>注：ACS3 端到端签名无官方 golden 向量，本测仅验结构 / 确定性 + 分支；真实签名正确性需对接真 API 联调验签。
 */
class AliyunSmsProviderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String PLAIN_PHONE = "+8613800138000";
  private static final List<String> PHONE_NUMBERS = List.of(PLAIN_PHONE);

  private SmsProperties properties() {
    SmsProperties props = new SmsProperties();
    props.setAliyunAccessKeyId("ak-test");
    props.setAliyunAccessKeySecret("sk-test");
    props.setAliyunEndpoint("dysmsapi.aliyuncs.com");
    return props;
  }

  private NotificationMessage message(String configJson) {
    WebhookEventPayload payload =
        new WebhookEventPayload("tenant-a", "JOB_FAILED", "jobs", "c1", Instant.EPOCH, null);
    return new NotificationMessage(
        "tenant-a", "ch-sms", "SMS", configJson, payload, "{\"jobId\":\"J1\"}");
  }

  private String fullConfig() {
    return "{\"signName\":\"阿里云\",\"templateCode\":\"SMS_123\"}";
  }

  @Test
  void supportsIsCaseInsensitive() {
    AliyunSmsProvider provider = new AliyunSmsProvider(properties(), objectMapper);
    assertThat(provider.supports("aliyun")).isTrue();
    assertThat(provider.supports("ALIYUN")).isTrue();
    assertThat(provider.supports("Aliyun")).isTrue();
    assertThat(provider.supports("tencent")).isFalse();
    assertThat(provider.supports(null)).isFalse();
  }

  @Test
  void missingSignNameFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    AliyunSmsProvider provider = providerRecording(called, null, "{\"Code\":\"OK\"}");

    WebhookDeliveryResult result =
        provider.send(PHONE_NUMBERS, message("{\"templateCode\":\"SMS_123\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms signName");
    assertThat(called.get()).isFalse();
  }

  @Test
  void missingTemplateCodeFailsWithoutNetworkCall() {
    AtomicBoolean called = new AtomicBoolean(false);
    AliyunSmsProvider provider = providerRecording(called, null, "{\"Code\":\"OK\"}");

    WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message("{\"signName\":\"阿里云\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorSummary()).isEqualTo("missing sms templateCode");
    assertThat(called.get()).isFalse();
  }

  @Test
  void codeOkIsSuccess() {
    AtomicReference<String> sentUrl = new AtomicReference<>();
    AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
    AliyunSmsProvider provider =
        new AliyunSmsProvider(properties(), objectMapper) {
          @Override
          protected String acsDate() {
            return "2026-06-24T00:00:00Z";
          }

          @Override
          protected String nonce() {
            return "fixednonce";
          }

          @Override
          protected String postJson(String url, Map<String, String> headers) {
            sentUrl.set(url);
            sentHeaders.set(headers);
            return "{\"Code\":\"OK\",\"Message\":\"OK\",\"BizId\":\"x\"}";
          }
        };

    WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message(fullConfig()));

    assertThat(result.success()).isTrue();
    // 结构 / 确定性断言:Action / 模板参数进 query,Authorization 头按 ACS3 构造。
    assertThat(sentUrl.get()).contains("TemplateCode=SMS_123");
    assertThat(sentUrl.get()).contains("TemplateParam=");
    assertThat(sentHeaders.get().get("x-acs-action")).isEqualTo("SendSms");
    assertThat(sentHeaders.get().get("x-acs-version")).isEqualTo("2017-05-25");
    assertThat(sentHeaders.get().get("Authorization"))
        .startsWith("ACS3-HMAC-SHA256 Credential=ak-test")
        .contains("SignedHeaders=")
        .contains("Signature=");
    // 签名确定:固定 date/nonce/secret → 相同 signature(防漂移)。
    String auth1 = sentHeaders.get().get("Authorization");
    provider.send(PHONE_NUMBERS, message(fullConfig()));
    assertThat(sentHeaders.get().get("Authorization")).isEqualTo(auth1);
  }

  @Test
  void nonOkCodeFails() {
    AliyunSmsProvider provider =
        new AliyunSmsProvider(properties(), objectMapper) {
          @Override
          protected String postJson(String url, Map<String, String> headers) {
            return "{\"Code\":\"isv.MOBILE_NUMBER_ILLEGAL\",\"Message\":\"bad\"}";
          }
        };

    WebhookDeliveryResult result = provider.send(PHONE_NUMBERS, message(fullConfig()));

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorSummary()).isEqualTo("sms code=isv.MOBILE_NUMBER_ILLEGAL");
  }

  @Test
  void logsDoNotContainPlainPhoneNumber() {
    // 通过自定义 appender 捕获本类日志,断言任何一条都不含手机号明文。
    Logger logger = (Logger) LoggerFactory.getLogger(AliyunSmsProvider.class);
    ListAppender appender = new ListAppender();
    appender.start();
    logger.addAppender(appender);
    try {
      AliyunSmsProvider provider =
          new AliyunSmsProvider(properties(), objectMapper) {
            @Override
            protected String postJson(String url, Map<String, String> headers) {
              return "{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\"}";
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

  private AliyunSmsProvider providerRecording(
      AtomicBoolean called, AtomicReference<String> urlSink, String response) {
    return new AliyunSmsProvider(properties(), objectMapper) {
      @Override
      protected String postJson(String url, Map<String, String> headers) {
        called.set(true);
        if (urlSink != null) {
          urlSink.set(url);
        }
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
