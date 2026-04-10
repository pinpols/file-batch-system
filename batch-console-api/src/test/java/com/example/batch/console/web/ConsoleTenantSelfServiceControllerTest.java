package com.example.batch.console.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleQuotaPolicyApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleSystemParameterService;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleTenantSelfServiceControllerTest {

    private final ConsoleQuotaPolicyApplicationService quotaPolicyService = org.mockito.Mockito.mock(ConsoleQuotaPolicyApplicationService.class);
    private final ConsoleSystemParameterService parameterService = org.mockito.Mockito.mock(ConsoleSystemParameterService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private final ConsoleTenantGuard tenantGuard = org.mockito.Mockito.mock(ConsoleTenantGuard.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
        when(requestMetadataResolver.current()).thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));
        when(tenantGuard.resolveTenant("t1")).thenReturn("t1");

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ConsoleTenantSelfServiceController(quotaPolicyService, parameterService, responseFactory, requestMetadataResolver, tenantGuard))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldReturnQuota() throws Exception {
        Map<String, Object> policy = Map.of("policyCode", "max-jobs", "limit", 100);
        when(quotaPolicyService.list("t1", null, null, 1, 100))
                .thenReturn(new PageResponse<>(1, 1, 100, List.of(policy)));

        mockMvc.perform(get("/api/console/tenants/quota").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].policyCode").value("max-jobs"));
    }

    @Test
    void shouldReturnUsage() throws Exception {
        when(parameterService.getValue("t1", "tenant.usage.running-jobs")).thenReturn(Optional.of("5"));
        when(parameterService.getValue("t1", "tenant.usage.daily-triggers")).thenReturn(Optional.of("120"));
        when(parameterService.getValue("t1", "tenant.usage.file-count")).thenReturn(Optional.of("42"));

        mockMvc.perform(get("/api/console/tenants/usage").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.runningJobs").value("5"))
                .andExpect(jsonPath("$.data.dailyTriggers").value("120"))
                .andExpect(jsonPath("$.data.fileCount").value("42"));
    }
}
