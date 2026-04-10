package com.example.batch.console.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.console.domain.entity.SystemParameterEntity;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleSystemParameterService;
import com.example.batch.console.support.ConsoleApiExceptionHandler;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleSystemParameterControllerTest {

    private final ConsoleSystemParameterService parameterService = org.mockito.Mockito.mock(ConsoleSystemParameterService.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
        ConsoleApiExceptionHandler exceptionHandler = new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());

        when(requestMetadataResolver.responseMeta()).thenReturn(new ResponseMeta("req-1", "trace-1", Instant.now()));
        when(requestMetadataResolver.current()).thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleSystemParameterController(parameterService, responseFactory, requestMetadataResolver))
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    @Test
    void shouldListParameters() throws Exception {
        SystemParameterEntity entity = new SystemParameterEntity();
        entity.setId(1L);
        entity.setTenantId("t1");
        entity.setParamKey("retry.max-count");
        entity.setParamValue("3");
        when(parameterService.list("t1")).thenReturn(List.of(entity));

        mockMvc.perform(get("/api/console/system-parameters").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].paramKey").value("retry.max-count"))
                .andExpect(jsonPath("$.data[0].paramValue").value("3"));
    }

    @Test
    void shouldGetParameterByKey() throws Exception {
        when(parameterService.getValue("t1", "retry.max-count")).thenReturn(Optional.of("3"));

        mockMvc.perform(get("/api/console/system-parameters/value")
                        .param("tenantId", "t1")
                        .param("key", "retry.max-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.key").value("retry.max-count"))
                .andExpect(jsonPath("$.data.value").value("3"));
    }

    @Test
    void shouldUpsertParameter() throws Exception {
        mockMvc.perform(put("/api/console/system-parameters")
                        .param("tenantId", "t1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"key":"retry.max-count","value":"5","description":"Max retry count"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(parameterService).upsert("t1", "retry.max-count", "5", "Max retry count", "operator-1");
    }

    @Test
    void shouldDeleteParameter() throws Exception {
        mockMvc.perform(delete("/api/console/system-parameters")
                        .param("tenantId", "t1")
                        .param("key", "retry.max-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(parameterService).delete("t1", "retry.max-count");
    }
}
