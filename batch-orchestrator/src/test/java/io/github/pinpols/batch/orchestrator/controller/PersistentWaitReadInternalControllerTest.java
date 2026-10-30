package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.application.service.PersistentWaitReadService;
import io.github.pinpols.batch.orchestrator.application.service.PersistentWaitReadService.PersistentWaitPage;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadQuery;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("持久等待内部查询参数绑定")
class PersistentWaitReadInternalControllerTest {

  @Mock
  private PersistentWaitReadService service;

  @Test
  @DisplayName("保留既有查询参数名称并解析业务日区间")
  void shouldBindExistingQueryParameters() throws Exception {
    when(service.list(any())).thenReturn(new PersistentWaitPage(List.of(), 2, 15, 0));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new PersistentWaitReadInternalController(service))
        .build();

    mvc.perform(get("/internal/ops/persistent-waits")
            .param("tenantId", "tenant-a")
            .param("waitType", "READINESS")
            .param("waitStatus", "PENDING")
            .param("workflowDefinitionId", "42")
            .param("bizDateFrom", "2026-10-01")
            .param("bizDateTo", "2026-10-26")
            .param("pageNo", "2")
            .param("pageSize", "15"))
        .andExpect(status().isOk());

    ArgumentCaptor<PersistentWaitReadQuery> query =
        ArgumentCaptor.forClass(PersistentWaitReadQuery.class);
    verify(service).list(query.capture());
    assertThat(query.getValue())
        .isEqualTo(new PersistentWaitReadQuery(
            "tenant-a",
            "READINESS",
            "PENDING",
            42L,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 26),
            2,
            15));
  }

  @Test
  @DisplayName("缺少租户参数时拒绝内部查询")
  void shouldRejectMissingTenant() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new PersistentWaitReadInternalController(service))
        .build();

    mvc.perform(get("/internal/ops/persistent-waits")).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
