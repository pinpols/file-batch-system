package io.github.pinpols.batch.console.domain.ops.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleAtomicRuntimeStatusService;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleAtomicRuntimeStatusResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Round-3 #8:{@link ConsoleAtomicRuntimeStatusController} MockMvc 测试 — 验证 endpoint path / 鉴权
 * annotation / 响应 envelope 结构。
 */
class ConsoleAtomicRuntimeStatusControllerTest {

  private final ConsoleAtomicRuntimeStatusService runtimeStatusService =
      mock(ConsoleAtomicRuntimeStatusService.class);
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
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleAtomicRuntimeStatusController(runtimeStatusService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .build();
  }

  @Test
  void shouldReturnRuntimeStatus_whenAtomicWorkerAvailable() throws Exception {
    ConsoleAtomicRuntimeStatusResponse resp =
        new ConsoleAtomicRuntimeStatusResponse(
            true,
            null,
            "atomic-node-1",
            "ATOMIC",
            Map.of("enabled", true, "commandWhitelistSize", 2),
            Map.of("enabled", true, "dialect", "PostgreSQL"),
            Map.of(
                "enabled",
                true,
                "enforceAllowlist",
                true,
                "enforceAllowlistSource",
                "prod-default",
                "allowlistHostsSize",
                3),
            Map.of("enabled", true, "allowedSchemasSize", 1));
    when(runtimeStatusService.fetch()).thenReturn(resp);

    mockMvc
        .perform(get("/api/console/ops/atomic-runtime-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(true))
        .andExpect(jsonPath("$.data.workerCode").value("atomic-node-1"))
        .andExpect(jsonPath("$.data.http.enforceAllowlistSource").value("prod-default"))
        .andExpect(jsonPath("$.data.sql.dialect").value("PostgreSQL"));
  }

  @Test
  void shouldReturnUnavailable_whenAtomicWorkerReverseChannelDisabled() throws Exception {
    when(runtimeStatusService.fetch())
        .thenReturn(
            ConsoleAtomicRuntimeStatusResponse.unavailable(
                "batch.console.atomic-worker.enabled=false"));

    mockMvc
        .perform(get("/api/console/ops/atomic-runtime-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(false))
        .andExpect(jsonPath("$.data.unavailableReason").exists());
  }
}
