package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.domain.entity.ApiKeyEntity;
import com.example.batch.console.service.ConsoleApiKeyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleApiKeyControllerTest {

  private final ConsoleApiKeyService apiKeyService = mock(ConsoleApiKeyService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleApiKeyController(
                    apiKeyService, responseFactory, requestMetadataResolver))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldListApiKeys() throws Exception {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setId(1L);
    entity.setTenantId("t1");
    entity.setKeyName("my-key");
    entity.setKeyPrefix("bk_abcde");
    entity.setEnabled(true);
    when(apiKeyService.list("t1")).thenReturn(List.of(entity));

    mockMvc
        .perform(get("/api/console/api-keys").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].keyName").value("my-key"))
        .andExpect(jsonPath("$.data[0].keyPrefix").value("bk_abcde"));
  }

  @Test
  void shouldCreateApiKey() throws Exception {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setId(1L);
    entity.setKeyName("my-key");
    entity.setKeyPrefix("bk_abcde");
    entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(apiKeyService.create(anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(new ConsoleApiKeyService.CreateResult(entity, "bk_raw_secret_key"));

    mockMvc
        .perform(
            post("/api/console/api-keys")
                .param("tenantId", "t1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {"keyName":"my-key","scopes":"read,write"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.keyName").value("my-key"))
        .andExpect(jsonPath("$.data.rawKey").value("bk_raw_secret_key"));
  }

  @Test
  void shouldRevokeApiKey() throws Exception {
    mockMvc
        .perform(delete("/api/console/api-keys/1").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(apiKeyService).revoke("t1", 1L, "operator-1");
  }
}
