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
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.entity.BusinessShardCatalogEntity;
import io.github.pinpols.batch.console.domain.param.BusinessShardCatalogUpsertParam;
import io.github.pinpols.batch.console.service.ConsoleBusinessShardCatalogService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** ConsoleBusinessShardCatalogController list/upsert/delete + 校验。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("业务分片目录控制器: 列表查询、写入委托、参数校验与删除")
class ConsoleBusinessShardCatalogControllerTest {

  @Mock
  private ConsoleBusinessShardCatalogService service;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

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
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleBusinessShardCatalogController(
            service, responseFactory, requestMetadataResolver))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("查询分片目录时, 响应返回分片键")
  void shouldReturnCatalogRows_whenListing() throws Exception {
    BusinessShardCatalogEntity row = new BusinessShardCatalogEntity();
    row.setPlacementKey("shard-1");
    row.setHost("db-1");
    when(service.list()).thenReturn(List.of(row));

    mockMvc
        .perform(get("/api/console/ops/shard-catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].placementKey").value("shard-1"));
  }

  @Test
  @DisplayName("写入分片目录时, 请求体组装成入参交给服务")
  void shouldDelegateUpsert_whenSavingCatalogRow() throws Exception {
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "trace-1", null, "ops:bob", null, null));
    mockMvc
        .perform(put("/api/console/ops/shard-catalog")
            .contentType(APPLICATION_JSON)
            .content("{\"placementKey\":\"shard-1\",\"host\":\"db-1\",\"port\":5432,"
                + "\"dbName\":\"batch_business\",\"enabled\":true}"))
        .andExpect(status().isOk());
    verify(service).upsert(any(BusinessShardCatalogUpsertParam.class));
  }

  @Test
  @DisplayName("分片键格式或端口取值非法时, 返回客户端错误")
  void shouldRejectRequest_whenKeyOrPortInvalid() throws Exception {
    mockMvc
        .perform(put("/api/console/ops/shard-catalog")
            .contentType(APPLICATION_JSON)
            .content("{\"placementKey\":\"Shard_1\",\"host\":\"db-1\",\"port\":70000,"
                + "\"dbName\":\"batch_business\",\"enabled\":true}"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("删除分片目录时, 路径中的分片键传给服务")
  void shouldDelegateDelete_whenRemovingCatalogRow() throws Exception {
    when(service.delete("shard-9")).thenReturn(true);
    mockMvc
        .perform(delete("/api/console/ops/shard-catalog/shard-9"))
        .andExpect(status().isOk());
    verify(service).delete("shard-9");
  }
}
