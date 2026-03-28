package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.web.response.ConsoleApprovalCommandResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleQueryControllerTest {

    private final ConsoleQueryApplicationService queryApplicationService = org.mockito.Mockito.mock(ConsoleQueryApplicationService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory);

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleQueryController(queryApplicationService, responseFactory))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldReturnApprovalDtos() throws Exception {
        when(queryApplicationService.approvals(any())).thenReturn(List.of(
                new ConsoleApprovalCommandResponse(1L, "t1", "appr-001", "DOWNLOAD", "DOWNLOAD", "FILE", "1001",
                        "{}", "PENDING", "req-1", null, null, null, "trace-1", "idem-1",
                        OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC), null,
                        OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
        ));

        mockMvc.perform(get("/api/console/query/approvals").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].approvalNo").value("appr-001"))
                .andExpect(jsonPath("$.data[0].approvalStatus").value("PENDING"));
    }

    @Test
    void shouldReturnAlertDtos() throws Exception {
        when(queryApplicationService.alertEvents(any())).thenReturn(List.of(
                new ConsoleAlertEventResponse(
                        1L,
                        "t1",
                        "console-api",
                        "FILE_ERROR",
                        "HIGH",
                        "file failed",
                        "{\"k\":\"v\"}",
                        "dedup-1",
                        2,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "trace-1",
                        "OPEN",
                        Instant.EPOCH,
                        Instant.EPOCH
                )
        ));

        mockMvc.perform(get("/api/console/query/alerts").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data[0].title").value("file failed"));
    }
}
