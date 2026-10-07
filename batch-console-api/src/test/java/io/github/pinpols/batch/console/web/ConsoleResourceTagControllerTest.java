package io.github.pinpols.batch.console.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.rbac.entity.ResourceTagEntity;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleResourceTagService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("资源标签控制器: 按资源与标签查询、写入与删除委托")
class ConsoleResourceTagControllerTest {

  private final ConsoleResourceTagService tagService = mock(ConsoleResourceTagService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "operator-1", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(
            new ConsoleResourceTagController(tagService, responseFactory, requestMetadataResolver))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("按资源查询标签时, 响应返回标签键与标签值")
  void shouldListTagsByResource() throws Exception {
    ResourceTagEntity entity = new ResourceTagEntity();
    entity.setId(1L);
    entity.setTenantId("t1");
    entity.setResourceType("JOB");
    entity.setResourceCode("job-001");
    entity.setTagKey("env");
    entity.setTagValue("prod");
    when(tagService.listByResource("t1", "JOB", "job-001")).thenReturn(List.of(entity));

    mockMvc
        .perform(get("/api/console/tags")
            .param("tenantId", "t1")
            .param("resourceType", "JOB")
            .param("resourceCode", "job-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].tagKey").value("env"))
        .andExpect(jsonPath("$.data[0].tagValue").value("prod"));
  }

  @Test
  @DisplayName("按标签键值搜索资源时, 响应返回命中的资源编码")
  void shouldSearchByTag() throws Exception {
    ResourceTagEntity entity = new ResourceTagEntity();
    entity.setId(1L);
    entity.setTenantId("t1");
    entity.setResourceType("JOB");
    entity.setResourceCode("job-001");
    entity.setTagKey("env");
    entity.setTagValue("prod");
    when(tagService.listByTagKey("t1", "env", "prod")).thenReturn(List.of(entity));

    mockMvc
        .perform(get("/api/console/tags/search")
            .param("tenantId", "t1")
            .param("tagKey", "env")
            .param("tagValue", "prod"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].resourceCode").value("job-001"));
  }

  @Test
  @DisplayName("写入资源标签时, 租户、资源、标签与操作人组装成入参")
  void shouldUpsertTag() throws Exception {
    mockMvc
        .perform(post("/api/console/tags")
            .param("tenantId", "t1")
            .contentType(APPLICATION_JSON)
            .content("""
                    {
                      "resourceType":"JOB",
                      "resourceCode":"job-001",
                      "tagKey":"env",
                      "tagValue":"prod"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(tagService).upsert("t1", "JOB", "job-001", "env", "prod", "operator-1");
  }

  @Test
  @DisplayName("删除资源标签时, 租户、资源与标签键传给服务")
  void shouldDeleteTag() throws Exception {
    mockMvc
        .perform(delete("/api/console/tags")
            .param("tenantId", "t1")
            .param("resourceType", "JOB")
            .param("resourceCode", "job-001")
            .param("tagKey", "env"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"));

    verify(tagService).delete("t1", "JOB", "job-001", "env");
  }
}
