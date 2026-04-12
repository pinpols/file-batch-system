package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.console.application.ConsoleFileDownloadApplicationService;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleFileDownloadControllerTest {

  private final ConsoleFileDownloadApplicationService applicationService =
      mock(ConsoleFileDownloadApplicationService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new ConsoleFileDownloadController(applicationService))
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldReturn200WithStreamWhenDownloadSucceeds() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.setContentDisposition(ContentDisposition.attachment().filename("test.csv").build());

    when(applicationService.download(anyString(), anyLong(), any()))
        .thenReturn(
            ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(new ByteArrayInputStream("data".getBytes()))));

    mockMvc
        .perform(get("/api/console/files/1/download").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE));
  }

  @Test
  void shouldReturn400WhenTenantIdMissing() throws Exception {
    mockMvc.perform(get("/api/console/files/1/download")).andExpect(status().isBadRequest());
  }
}
