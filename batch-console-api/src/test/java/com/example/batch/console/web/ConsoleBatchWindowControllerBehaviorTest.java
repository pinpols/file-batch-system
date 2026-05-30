package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.job.application.ConsoleBatchWindowApplicationService;
import com.example.batch.console.domain.job.web.request.BatchWindowCreateRequest;
import com.example.batch.console.domain.job.web.request.BatchWindowUpdateRequest;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P1: ConsoleBatchWindowController CRUD 行为(原 ValidationTest 仅守 @ValidResourceCode)。 */
class ConsoleBatchWindowControllerBehaviorTest {

  private final ConsoleBatchWindowApplicationService service =
      mock(ConsoleBatchWindowApplicationService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleBatchWindowController(service, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void listShouldDelegateWithDefaults() throws Exception {
    when(service.list(eq("ta"), any(), any(), eq(1), eq(20)))
        .thenReturn(new PageResponse<>(0L, 1, 20, List.of()));
    mockMvc
        .perform(get("/api/console/batch-windows").param("tenantId", "ta"))
        .andExpect(status().isOk());
    verify(service).list("ta", null, null, 1, 20);
  }

  @Test
  void createShouldReturnRow() throws Exception {
    when(service.create(any(BatchWindowCreateRequest.class)))
        .thenReturn(Map.of("id", 1L, "windowCode", "always-open"));
    mockMvc
        .perform(
            post("/api/console/batch-windows")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"windowCode\":\"always-open\",\"timezone\":\"Asia/Shanghai\",\"startTime\":\"00:00\",\"endTime\":\"23:59\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.windowCode").value("always-open"));
  }

  @Test
  void updateShouldPassPathId() throws Exception {
    when(service.update(eq(7L), any(BatchWindowUpdateRequest.class))).thenReturn(Map.of("id", 7L));
    mockMvc
        .perform(
            put("/api/console/batch-windows/7")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"tenantId\":\"ta\",\"windowCode\":\"w1\",\"timezone\":\"Asia/Shanghai\",\"startTime\":\"00:00\",\"endTime\":\"23:59\"}"))
        .andExpect(status().isOk());
    verify(service).update(eq(7L), any(BatchWindowUpdateRequest.class));
  }

  @Test
  void toggleShouldPassEnabledFlag() throws Exception {
    mockMvc
        .perform(
            post("/api/console/batch-windows/9/toggle")
                .param("tenantId", "ta")
                .param("enabled", "true"))
        .andExpect(status().isOk());
    verify(service).toggle(9L, "ta", true);
  }
}
