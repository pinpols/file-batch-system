package com.example.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.entity.ArchivePolicyEntity;
import com.example.batch.console.domain.param.ArchivePolicyUpsertParam;
import com.example.batch.console.service.ConsoleArchivePolicyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleArchivePolicyControllerTest {

  private final ConsoleArchivePolicyService archivePolicyService =
      mock(ConsoleArchivePolicyService.class);
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
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleArchivePolicyController(
                    archivePolicyService, responseFactory, requestMetadataResolver))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldListPolicies() throws Exception {
    ArchivePolicyEntity entity = new ArchivePolicyEntity();
    entity.setId(1L);
    entity.setTenantId("t1");
    entity.setTargetTable("job_instance");
    entity.setRetentionDays(90);
    entity.setArchiveEnabled(true);
    entity.setCleanupEnabled(false);
    entity.setBatchSize(1000);
    when(archivePolicyService.list("t1")).thenReturn(List.of(entity));

    mockMvc
        .perform(get("/api/console/ops/archive-policies").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].targetTable").value("job_instance"))
        .andExpect(jsonPath("$.data[0].retentionDays").value(90));
  }

  @Test
  void shouldUpsertPolicy() throws Exception {
    mockMvc
        .perform(
            put("/api/console/ops/archive-policies")
                .param("tenantId", "t1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "targetTable":"job_instance",
                      "retentionDays":90,
                      "archiveEnabled":true,
                      "cleanupEnabled":false,
                      "batchSize":1000,
                      "description":"Archive job instances"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    ArchivePolicyUpsertParam expected =
        ArchivePolicyUpsertParam.builder()
            .tenantId("t1")
            .targetTable("job_instance")
            .retentionDays(90)
            .archiveEnabled(true)
            .batchSize(1000)
            .description("Archive job instances")
            .operator("operator-1")
            .build();
    verify(archivePolicyService).upsert(expected);
  }
}
