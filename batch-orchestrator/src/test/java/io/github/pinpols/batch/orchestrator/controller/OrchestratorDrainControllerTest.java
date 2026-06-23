package io.github.pinpols.batch.orchestrator.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OrchestratorDrainControllerTest {

  @Mock private OrchestratorGracefulShutdown gracefulShutdown;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new OrchestratorDrainController(gracefulShutdown)).build();
  }

  @Test
  void shouldReturnDrainStatus() throws Exception {
    when(gracefulShutdown.status()).thenReturn(Map.of("draining", false, "reason", "none"));

    mockMvc
        .perform(get("/internal/orchestrator/drain/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draining").value(false));
  }

  @Test
  void shouldEnableDrain() throws Exception {
    when(gracefulShutdown.status()).thenReturn(Map.of("draining", true, "reason", "manual-enable"));

    mockMvc
        .perform(post("/internal/orchestrator/drain/enable"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draining").value(true));

    verify(gracefulShutdown).startDraining("manual-enable");
  }

  @Test
  void shouldDisableDrain() throws Exception {
    when(gracefulShutdown.status())
        .thenReturn(Map.of("draining", false, "reason", "manual-disable"));

    mockMvc
        .perform(post("/internal/orchestrator/drain/disable"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draining").value(false));

    verify(gracefulShutdown).stopDraining("manual-disable");
  }
}
