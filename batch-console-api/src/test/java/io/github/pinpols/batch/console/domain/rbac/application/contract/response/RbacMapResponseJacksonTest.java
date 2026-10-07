package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：批次4 rbac(auth/captcha/api-key/tenant self-service) 域类型化 response record 的 JSON key
 * 必须与历史 Map 响应逐字一致。这些端点原由 controller 内 {@code Map.of}/{@code LinkedHashMap} 构建，转换后由 record 直接承载。
 */
@DisplayName("权限域响应对象: 序列化字段与历史映射一致")
class RbacMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }

  @Test
  @DisplayName("登录公钥响应序列化后保持三个字段")
  void shouldKeepThreeKeysForLoginPublicKey() throws Exception {
    Map<String, Object> back =
        roundTrip(new ConsoleLoginPublicKeyResponse("RSA-OAEP-256", "PEM", "fp"));
    assertThat(back).containsOnlyKeys("algorithm", "publicKey", "fingerprint");
    assertThat(back).containsEntry("algorithm", "RSA-OAEP-256").containsEntry("publicKey", "PEM");
  }

  @Test
  @DisplayName("流票据响应序列化后仅保留票据字段")
  void shouldKeepSingleKeyForStreamTicket() throws Exception {
    Map<String, Object> back = roundTrip(new ConsoleStreamTicketResponse("tkt"));
    assertThat(back).containsOnlyKeys("ticket").containsEntry("ticket", "tkt");
  }

  @Test
  @DisplayName("验证码配置响应序列化后保持三个字段")
  void shouldKeepThreeKeysForCaptchaConfig() throws Exception {
    Map<String, Object> back = roundTrip(new ConsoleCaptchaConfigResponse("self", "site", true));
    assertThat(back).containsOnlyKeys("provider", "siteKey", "loginProtectionEnabled");
    assertThat(back)
        .containsEntry("provider", "self")
        .containsEntry("loginProtectionEnabled", true);
  }

  @Test
  @DisplayName("访问密钥创建响应序列化后保持五个字段")
  void shouldKeepFiveKeysForApiKeyCreate() throws Exception {
    Map<String, Object> back = roundTrip(new ConsoleApiKeyCreateResponse(
        1L, "k1", "abcd1234", "raw-secret", Instant.parse("2026-07-11T00:00:00Z")));
    assertThat(back).containsOnlyKeys("id", "keyName", "keyPrefix", "rawKey", "createdAt");
    assertThat(back).containsEntry("id", 1).containsEntry("rawKey", "raw-secret");
  }

  @Test
  @DisplayName("用量概览缺失的系统参数不输出, 存在的参数保留")
  void shouldOmitAbsentParametersButKeepPresentOnesForTenantUsage() throws Exception {
    // 历史 wire：系统参数缺失时对应键不出现（NON_NULL）。此处只有 runningJobs 命中。
    Map<String, Object> back = roundTrip(new ConsoleTenantUsageSummaryResponse("3", null, null));
    assertThat(back).containsOnlyKeys("runningJobs").containsEntry("runningJobs", "3");

    Map<String, Object> all = roundTrip(new ConsoleTenantUsageSummaryResponse("3", "10", "42"));
    assertThat(all).containsOnlyKeys("runningJobs", "dailyTriggers", "fileCount");
  }
}
