package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleJobControllerTest {

    private final ConsoleJobApplicationService applicationService = org.mockito.Mockito.mock(ConsoleJobApplicationService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleJobController(applicationService, responseFactory))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/console/jobs/trigger")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        verifyNoInteractions(applicationService);
    }

    @Test
    void shouldTriggerAndReturnCommonResponseOnSuccess() throws Exception {
        when(applicationService.trigger(any(), anyString())).thenReturn("OK");

        mockMvc.perform(post("/api/console/jobs/trigger")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value("OK"));
    }
}
