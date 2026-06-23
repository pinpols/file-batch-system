package io.github.pinpols.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import io.github.pinpols.batch.console.service.ConsoleBusinessTenantPlacementService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** ConsoleBusinessTenantPlacementController list/upsert/delete + 校验。 */
@ExtendWith(MockitoExtension.class)
class ConsoleBusinessTenantPlacementControllerTest {

  @Mock private ConsoleBusinessTenantPlacementService service;
  @Mock private ConsoleRequestMetadataResolver requestMetadataResolver;

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
                new ConsoleBusinessTenantPlacementController(
                    service, responseFactory, requestMetadataResolver))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void listShouldReturnPlacements() throws Exception {
    BusinessTenantPlacementEntity row = new BusinessTenantPlacementEntity();
    row.setTenantId("t-1");
    row.setPlacementKey("silo-big");
    when(service.list()).thenReturn(List.of(row));

    mockMvc
        .perform(get("/api/console/ops/tenant-placements"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].placementKey").value("silo-big"));
  }

  @Test
  void upsertShouldDelegateWithOperator() throws Exception {
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", null, "ops:alice", null, null));

    mockMvc
        .perform(
            put("/api/console/ops/tenant-placements")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t-1\",\"placementKey\":\"shard-1\"}"))
        .andExpect(status().isOk());
    verify(service).upsert(any(BusinessTenantPlacementUpsertParam.class));
  }

  @Test
  void upsertShouldRejectInvalidPlacementKey() throws Exception {
    mockMvc
        .perform(
            put("/api/console/ops/tenant-placements")
                .contentType(APPLICATION_JSON)
                .content("{\"tenantId\":\"t-1\",\"placementKey\":\"Shard_1!\"}"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void deleteShouldDelegate() throws Exception {
    when(service.delete("t-1")).thenReturn(true);
    mockMvc.perform(delete("/api/console/ops/tenant-placements/t-1")).andExpect(status().isOk());
    verify(service).delete("t-1");
  }
}
