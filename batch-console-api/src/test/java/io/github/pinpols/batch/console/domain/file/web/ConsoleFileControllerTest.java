package io.github.pinpols.batch.console.domain.file.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileOperationResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.response.file.ConsolePresignDownloadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleFileControllerTest {

  private final ConsoleFileApplicationService applicationService =
      mock(ConsoleFileApplicationService.class);
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
                new ConsoleFileController(applicationService, responseFactory))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/console/files/archive")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","fileId":1,"reason":"ok"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

    verifyNoInteractions(applicationService);
  }

  @Test
  void shouldArchiveAndReturnCommonResponseOnSuccess() throws Exception {
    when(applicationService.archive(any(), anyString()))
        .thenReturn(new ConsoleFileOperationResponse("OK"));

    mockMvc
        .perform(
            post("/api/console/files/archive")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","fileId":1,"reason":"ok"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.status").value("OK"));
  }

  @Test
  void shouldPresignDownloadAndReturnCommonResponseOnSuccess() throws Exception {
    when(applicationService.presignDownload(any(), anyString()))
        .thenReturn(new ConsolePresignDownloadResponse("appr-001", null));

    mockMvc
        .perform(
            post("/api/console/files/presign-download")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"tenantId":"t1","fileId":1,"reason":"ok"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.approvalNo").value("appr-001"));
  }

  @Test
  void shouldUploadContentAndReturnCommonResponseOnSuccess() throws Exception {
    when(applicationService.uploadContent(anyString(), any(), any(), anyString()))
        .thenReturn(new ConsoleFileOperationResponse("UPLOADED"));
    MockMultipartFile file =
        new MockMultipartFile("file", "a.csv", "text/csv", "id,name\n1,A\n".getBytes());

    mockMvc
        .perform(
            multipart("/api/console/files/{fileId}/content", 1L)
                .file(file)
                .param("tenantId", "t1")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-003")
                .with(
                    request -> {
                      request.setMethod("PUT");
                      return request;
                    }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.status").value("UPLOADED"));
  }
}
