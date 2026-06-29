package io.github.pinpols.batch.console.domain.file.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileTemplateApplicationService;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftResult;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleFileTemplateControllerTest {

  private final ConsoleFileTemplateApplicationService applicationService =
      mock(ConsoleFileTemplateApplicationService.class);
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

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleFileTemplateController(applicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn200WhenGetTemplateById() throws Exception {
    when(applicationService.get(anyLong(), anyString()))
        .thenReturn(Map.of("id", 1L, "tenantId", "t1"));

    mockMvc
        .perform(get("/api/console/file-templates/1").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenantId").value("t1"));
  }

  @Test
  void shouldReturn400WhenCreateRequestMissingTemplateCode() throws Exception {
    mockMvc
        .perform(post("/api/console/file-templates").contentType(APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    verifyNoInteractions(applicationService);
  }

  @Test
  void shouldReturn200WhenToggleTemplate() throws Exception {
    mockMvc
        .perform(
            patch("/api/console/file-templates/1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\",\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }

  @Test
  void shouldReturn400WhenToggleTemplateMissingTenantId() throws Exception {
    mockMvc
        .perform(
            patch("/api/console/file-templates/1")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    verifyNoInteractions(applicationService);
  }

  @Test
  void shouldReturn200WhenUpdateTemplate() throws Exception {
    when(applicationService.update(anyLong(), any()))
        .thenReturn(Map.of("id", 1L, "tenantId", "t1"));

    mockMvc
        .perform(
            put("/api/console/file-templates/1")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));
  }

  @Test
  void shouldReturn200WhenDraftMapping() throws Exception {
    when(applicationService.draftMapping(any()))
        .thenReturn(
            new FileTemplateMappingDraftResult(
                "IMPORT",
                "[{\"name\":\"orderNo\"}]",
                "{\"jdbcMappedImport\":{}}",
                null,
                List.of()));

    mockMvc
        .perform(
            post("/api/console/file-templates/mapping-draft")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","direction":"IMPORT","fields":[{"sourceColumn":"orderNo"}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.direction").value("IMPORT"))
        .andExpect(jsonPath("$.data.fieldMappingsJson").value("[{\"name\":\"orderNo\"}]"));
  }
}
