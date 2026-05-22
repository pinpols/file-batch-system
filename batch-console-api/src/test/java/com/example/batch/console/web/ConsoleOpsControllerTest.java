package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.ops.ConsoleOpsApplicationService;
import com.example.batch.console.application.ops.ConsoleOutboxOpsApplicationService;
import com.example.batch.console.service.ConsoleKafkaLagQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ops.ConsoleOpsSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleOpsControllerTest {

  private final ConsoleOpsApplicationService opsApplicationService =
      mock(ConsoleOpsApplicationService.class);
  private final ConsoleOutboxOpsApplicationService outboxOpsService =
      mock(ConsoleOutboxOpsApplicationService.class);
  private final ConsoleKafkaLagQueryService kafkaLagQueryService =
      mock(ConsoleKafkaLagQueryService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleOpsController(
                    opsApplicationService,
                    outboxOpsService,
                    responseFactory,
                    kafkaLagQueryService,
                    passThroughCache()))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn200AndCommonResponseStructureOnSuccess() throws Exception {
    when(opsApplicationService.summary(anyString()))
        .thenReturn(
            new ConsoleOpsSummaryResponse("t1", 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));

    mockMvc
        .perform(get("/api/console/ops/summary").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenantId").value("t1"))
        .andExpect(jsonPath("$.data.pendingApprovals").value(1))
        .andExpect(jsonPath("$.data.openAlerts").value(2))
        .andExpect(jsonPath("$.data.criticalAlerts").value(3));
  }

  private static ConsoleQueryCacheService passThroughCache() {
    ConsoleQueryCacheService cache = mock(ConsoleQueryCacheService.class);
    when(cache.getOrLoad(anyString(), any(), any(), any()))
        .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(3)).get());
    return cache;
  }
}
