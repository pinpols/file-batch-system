package io.github.pinpols.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApplicationService;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigChangeLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigReleaseResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.view.ConsoleSecretVersionResponse;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("配置管理控制器: 发布单与密钥、变更日志查询及废弃发布端点")
@ExtendWith(MockitoExtension.class)
class ConsoleConfigControllerTest {

  @Mock
  private ConsoleConfigApplicationService configApplicationService;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleConfigController(configApplicationService, responseFactory))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("查询配置发布单列表时, 响应返回配置键")
  void shouldReturnConfigReleaseListAsDtos() throws Exception {
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(configApplicationService.configReleases(any()))
        .thenReturn(List.of(ConsoleConfigReleaseResponse.builder()
            .id(1L)
            .tenantId("t1")
            .configType("FILE")
            .configKey("KEY")
            .configName("Name")
            .configStatus("DRAFT")
            .versionNo(1)
            .configSource("DYNAMIC_DB")
            .activationMode("IMMEDIATE_AFTER_CONFIRMATION")
            .restartRequired(false)
            .applyConfirmationStatus("NOT_RELEASED")
            .grayScopeJson("{}")
            .configPayloadJson("{\"a\":1}")
            .effectiveFromAt(Instant.EPOCH)
            .effectiveToAt(Instant.EPOCH)
            .createdBy("u1")
            .updatedBy("u2")
            .createdAt(Instant.EPOCH)
            .updatedAt(Instant.EPOCH)
            .build()));

    mockMvc
        .perform(get("/api/console/config/releases").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].configKey").value("KEY"));
  }

  @Test
  @DisplayName("调用已废弃的直接发布端点时, 返回资源已下线")
  void shouldReturnGoneForDeprecatedDirectPublishEndpoint() throws Exception {
    mockMvc
        .perform(post("/api/console/config/releases/1/publish")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-1")
            .contentType(APPLICATION_JSON)
            .content("""
                    {"tenantId":"t1","operatorId":"u1","traceId":"trace-1","reason":"ok","expectedVersionNo":1}
                    """))
        .andExpect(status().isGone());
  }

  @Test
  @DisplayName("查询密钥版本与变更日志时, 响应分别返回密钥引用与变更动作")
  void shouldReturnSecretVersionAndChangeLogDtos() throws Exception {
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(configApplicationService.secretVersions(any()))
        .thenReturn(List.of(ConsoleSecretVersionResponse.builder()
            .id(1L)
            .tenantId("t1")
            .secretRef("REF")
            .secretName("Secret")
            .versionNo(2)
            .secretStatus("ACTIVE")
            .currentVersion(true)
            .rotationWindowStartAt(Instant.EPOCH)
            .rotationWindowEndAt(Instant.EPOCH)
            .effectiveFromAt(Instant.EPOCH)
            .effectiveToAt(Instant.EPOCH)
            .rotationReason("reason")
            .createdBy("u1")
            .updatedBy("u2")
            .createdAt(Instant.EPOCH)
            .updatedAt(Instant.EPOCH)
            .build()));
    when(configApplicationService.configChangeLogs(any()))
        .thenReturn(List.of(ConsoleConfigChangeLogResponse.builder()
            .id(2L)
            .tenantId("t1")
            .configType("FILE")
            .configKey("KEY")
            .versionNo(1)
            .changeAction("CREATE")
            .changeResult("SUCCESS")
            .operatorType("API")
            .operatorId("u1")
            .traceId("trace-1")
            .changeSummaryJson("{}")
            .createdAt(Instant.EPOCH)
            .build()));

    mockMvc
        .perform(get("/api/console/config/secrets").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].secretRef").value("REF"));

    mockMvc
        .perform(get("/api/console/config/change-logs").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].changeAction").value("CREATE"));
  }

  @Test
  @DisplayName("撤销指定密钥版本时, 将租户与原因交给应用服务并返回成功")
  void shouldRevokeSecretVersion() throws Exception {
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    mockMvc
        .perform(post("/api/console/config/secrets/42/revoke")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-42")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"t1","reason":"credential compromised"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(configApplicationService).revokeSecretVersion(ArgumentMatchers.eq(42L), any());
  }
}
