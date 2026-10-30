package io.github.pinpols.batch.console.domain.rbac.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("租户配置初始化 HTTP 集合条目校验")
@ExtendWith(MockitoExtension.class)
class ConsoleTenantConfigInitValidationTest {
  @Mock
  private ConsoleTenantConfigInitApplicationService applicationService;

  @Mock
  private ConsoleTenantConfigCopyService copyService;

  @Mock
  private ConsoleResponseFactory responseFactory;

  private LocalValidatorFactoryBean validator;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc = MockMvcBuilders.standaloneSetup(
            new ConsoleTenantConfigInitController(applicationService, copyService, responseFactory))
        .setValidator(validator)
        .build();
  }

  @AfterEach
  void closeValidator() {
    validator.close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"jobDefinitions\":[null]",
        "\"workflowDefinitions\":[null]",
        "\"fileChannels\":[null]",
        "\"fileTemplates\":[null]",
        "\"resourceQueues\":[null]",
        "\"batchWindows\":[null]",
        "\"businessCalendars\":[null]",
        "\"quotaPolicies\":[null]",
        "\"alertRoutings\":[null]",
        "\"jobDefinitions\":[{\"jobCode\":\"JOB_A\",\"dependencyRules\":[null]}]",
        "\"workflowDefinitions\":[{\"workflowCode\":\"WF_A\",\"nodes\":[null]}]",
        "\"workflowDefinitions\":[{\"workflowCode\":\"WF_A\",\"edges\":[null]}]"
      })
  @DisplayName("顶层配置和嵌套依赖、节点及边的空条目在执行租户事务前返回400")
  void shouldRejectNullConfigurationItemsBeforeApplicationCall(String fragment) throws Exception {
    String body = "{\"targetTenantIds\":[\"tenant-a\"]," + fragment + "}";

    mvc.perform(post("/api/console/config/tenant-init")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "null-item")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(applicationService, copyService);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"targetTenantIds\":[\"tenant-a\"]}",
        "{\"targetTenantIds\":[\"tenant-a\"],\"jobDefinitions\":[],\"workflowDefinitions\":[]}"
      })
  @DisplayName("未提供配置列表和空列表仍允许进入初始化应用边界")
  void shouldAllowOmittedAndEmptyConfigurationLists(String body) throws Exception {
    mvc.perform(post("/api/console/config/tenant-init")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "empty-items")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    verify(applicationService)
        .batchInit(any(TenantConfigBatchInitRequest.class), eq("system"), anyString());
  }
}
