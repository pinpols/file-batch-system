package io.github.pinpols.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import io.github.pinpols.batch.console.service.ConsoleAssetFreshnessPolicyService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleAssetFreshnessPolicyControllerTest {

  private final ConsoleAssetFreshnessPolicyService policyService =
      mock(ConsoleAssetFreshnessPolicyService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleAssetFreshnessPolicyController(policyService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldListPolicies() throws Exception {
    AssetFreshnessPolicyEntity entity =
        new AssetFreshnessPolicyEntity(
            1L,
            "t1",
            "JOB_A",
            "JOB",
            LocalTime.of(2, 0),
            "Asia/Shanghai",
            300,
            2,
            "WARN",
            true,
            null,
            null);
    when(policyService.list("t1", "JOB_A", true, 100)).thenReturn(List.of(entity));

    mockMvc
        .perform(
            get("/api/console/asset-freshness-policies")
                .param("tenantId", "t1")
                .param("assetCode", "JOB_A")
                .param("enabled", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].assetCode").value("JOB_A"))
        .andExpect(jsonPath("$.data[0].severity").value("WARN"));
  }

  @Test
  void shouldCreatePolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/console/asset-freshness-policies")
                .param("tenantId", "t1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "assetCode":"JOB_A",
                      "expectedByLocalTime":"02:00:00",
                      "timezone":"Asia/Shanghai",
                      "staleAfterSeconds":300,
                      "lookbackDays":2,
                      "severity":"WARN",
                      "enabled":true
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(policyService)
        .upsert(
            AssetFreshnessPolicyUpsertParam.builder()
                .tenantId("t1")
                .assetCode("JOB_A")
                .expectedByLocalTime(LocalTime.of(2, 0))
                .timezone("Asia/Shanghai")
                .staleAfterSeconds(300)
                .lookbackDays(2)
                .severity("WARN")
                .enabled(true)
                .build());
  }

  @Test
  void shouldTogglePolicy() throws Exception {
    mockMvc
        .perform(
            patch("/api/console/asset-freshness-policies/9/enabled")
                .param("tenantId", "t1")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(policyService).setEnabled("t1", 9L, false);
  }
}
