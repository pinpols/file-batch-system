package io.github.pinpols.batch.orchestrator.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("排空控制接口的状态查询与人工启停,验证响应结果,状态标记与对排空流程的委托")
class OrchestratorDrainControllerTest {

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new OrchestratorDrainController(gracefulShutdown))
        .build();
  }

  @Test
  @DisplayName("查询排空状态时返回成功,并透出当前未排空的状态标记")
  void shouldReturnDrainStatus() throws Exception {
    when(gracefulShutdown.status())
        .thenReturn(new OrchestratorGracefulShutdown.DrainStatus(false, null, "none"));

    mockMvc
        .perform(get("/internal/orchestrator/drain/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draining").value(false));
  }

  @Test
  @DisplayName("开启排空时返回成功并透出已排空标记,同时以人工原因启动排空流程")
  void shouldEnableDrain() throws Exception {
    when(gracefulShutdown.status())
        .thenReturn(new OrchestratorGracefulShutdown.DrainStatus(true, null, "manual-enable"));

    mockMvc
        .perform(post("/internal/orchestrator/drain/enable"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draining").value(true));

    verify(gracefulShutdown).startDraining("manual-enable");
  }

  @Test
  @DisplayName("关闭排空时返回成功并透出未排空标记,同时以人工原因停止排空流程")
  void shouldDisableDrain() throws Exception {
    when(gracefulShutdown.status())
        .thenReturn(new OrchestratorGracefulShutdown.DrainStatus(false, null, "manual-disable"));

    mockMvc
        .perform(post("/internal/orchestrator/drain/disable"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draining").value(false));

    verify(gracefulShutdown).stopDraining("manual-disable");
  }
}
