package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.example.batch.console.application.ConsoleAlertApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleAlertActionResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleAlertControllerTest {

    private final ConsoleAlertApplicationService alertApplicationService = org.mockito.Mockito.mock(ConsoleAlertApplicationService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleAlertController(alertApplicationService, responseFactory))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/console/alerts/100/ack")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","operatorId":"u1","reason":"ok"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        verifyNoInteractions(alertApplicationService);
    }

    @Test
    void shouldAckAlertAndReturnCommonResponseOnSuccess() throws Exception {
        when(alertApplicationService.ack(anyLong(), any(), anyString())).thenReturn(
                new ConsoleAlertActionResponse(100L, "t1", "ack", "ACKED"));

        mockMvc.perform(post("/api/console/alerts/100/ack")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","operatorId":"u1","reason":"ok"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("ACKED"));
    }

    @Test
    void shouldReturn400WhenRequestBodyInvalid() throws Exception {
        mockMvc.perform(post("/api/console/alerts/100/close")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"","operatorId":"","reason":"ok"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
