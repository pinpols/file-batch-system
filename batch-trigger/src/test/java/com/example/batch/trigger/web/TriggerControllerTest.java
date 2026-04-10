package com.example.batch.trigger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import com.example.batch.trigger.service.TriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;

@SuppressWarnings("removal")
class TriggerControllerTest {

    private final TriggerService triggerService = mock(TriggerService.class);
    private final TriggerGracefulShutdown triggerGracefulShutdown = mock(TriggerGracefulShutdown.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TriggerController(triggerService, triggerGracefulShutdown))
            .setControllerAdvice(new TriggerApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new ObjectMapper().registerModule(new JavaTimeModule())
            ))
            .build();

    @Test
    void shouldGenerateRequestAndTraceIdsWhenHeadersAreMissing() throws Exception {
        when(triggerService.launch(any())).thenReturn(new LaunchResponse("inst-001", "trace-response"));

        mockMvc.perform(post("/api/triggers/launch")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "t1",
                                  "jobCode": "IMPORT_JOB",
                                  "bizDate": "2026-03-27",
                                  "triggerType": "API"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.instanceNo").value("inst-001"));

        ArgumentCaptor<TriggerLaunchCommand> captor = ArgumentCaptor.forClass(TriggerLaunchCommand.class);
        verify(triggerService).launch(captor.capture());
        TriggerLaunchCommand command = captor.getValue();
        assertThat(command.idempotencyKey()).isEqualTo("idem-001");
        assertThat(command.requestId()).startsWith("req-");
        assertThat(command.traceId()).hasSize(32);
    }

    @Test
    void shouldReturnValidationErrorWhenRequiredFieldsAreMissing() throws Exception {
        mockMvc.perform(post("/api/triggers/launch")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-002")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "",
                                  "bizDate": "2026-03-27"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(containsString("tenantId is required")));

        verifyNoInteractions(triggerService);
    }

    @Test
    void shouldReturnMissingIdempotencyKeyWhenHeaderIsAbsent() throws Exception {
        mockMvc.perform(post("/api/triggers/launch")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "t1",
                                  "jobCode": "IMPORT_JOB",
                                  "bizDate": "2026-03-27",
                                  "triggerType": "API"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        verifyNoInteractions(triggerService);
    }

    @Test
    void shouldApproveCatchUpWhenPayloadIsValid() throws Exception {
        when(triggerService.approvePendingCatchUp(any())).thenReturn(new LaunchResponse("inst-cu", "trace-cu"));

        mockMvc.perform(post("/api/triggers/catch-up/approve")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cu-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "t1",
                                  "requestId": "req-cu-1",
                                  "reason": "approved"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.instanceNo").value("inst-cu"));

        verify(triggerService).approvePendingCatchUp(any());
    }

    @Test
    void shouldReturnValidationErrorWhenCatchUpApprovalTenantIsMissing() throws Exception {
        mockMvc.perform(post("/api/triggers/catch-up/approve")
                        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-003")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "",
                                  "requestId": "req-001",
                                  "reason": "ok"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(containsString("must not be blank")));

        verifyNoInteractions(triggerService);
    }
}
