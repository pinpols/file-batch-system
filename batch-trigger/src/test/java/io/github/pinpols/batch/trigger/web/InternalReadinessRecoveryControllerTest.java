package io.github.pinpols.batch.trigger.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.readiness.ReadinessRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("Readiness 恢复 HTTP 入口拒绝不完整身份")
class InternalReadinessRecoveryControllerTest {

  @Mock private TriggerDefinitionLoader definitionLoader;
  @Mock private TriggerService triggerService;

  @ParameterizedTest
  @ValueSource(strings = {
    "{\"workflowDefinitionId\":1,\"scheduledFireTime\":\"2026-10-26T00:00:00Z\"}",
    "{\"tenantId\":\"  \",\"workflowDefinitionId\":1,\"scheduledFireTime\":\"2026-10-26T00:00:00Z\"}",
    "{\"tenantId\":\"tenant-a\",\"workflowDefinitionId\":0,\"scheduledFireTime\":\"2026-10-26T00:00:00Z\"}",
    "{\"tenantId\":\"tenant-a\",\"workflowDefinitionId\":-1,\"scheduledFireTime\":\"2026-10-26T00:00:00Z\"}"
  })
  @DisplayName("缺失租户或非正 Workflow 标识返回 400 和 INVALID_REQUEST")
  void shouldMapInvalidIdentityToBadRequest(String body) throws Exception {
    ReadinessRecoveryService service = new ReadinessRecoveryService(definitionLoader, triggerService);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalReadinessRecoveryController(service))
        .setMessageConverters(new JacksonJsonHttpMessageConverter())
        .build();

    mvc.perform(post("/internal/trigger/readiness-waits/recover")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    verifyNoInteractions(definitionLoader, triggerService);
  }
}
