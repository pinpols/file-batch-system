package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.application.service.dq.DataQualityRuleApplicationService;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据质量规则更新接口")
class DataQualityRuleUpdateControllerTest {

  @Mock
  private DataQualityRuleMapper mapper;

  @ParameterizedTest
  @CsvSource({"tenant-a, 999", "tenant-b, 42"})
  @DisplayName("不存在的规则或其他租户的规则更新零行时返回 404")
  void shouldReturnNotFoundWhenNoTenantScopedRowWasUpdated(String tenantId, long id)
      throws Exception {
    when(mapper.update(any(DataQualityRuleEntity.class))).thenReturn(0);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DataQualityRuleController(new DataQualityRuleApplicationService(mapper)))
        .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
        .build();

    mvc.perform(put("/internal/orchestrator/dq/rules/{id}", id)
            .param("tenantId", tenantId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tenantId\":\"tenant-a\",\"ruleCode\":\"RULE\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    ArgumentCaptor<DataQualityRuleEntity> rule =
        ArgumentCaptor.forClass(DataQualityRuleEntity.class);
    verify(mapper).update(rule.capture());
    assertThat(rule.getValue().getTenantId()).isEqualTo(tenantId);
    assertThat(rule.getValue().getId()).isEqualTo(id);
  }
}
