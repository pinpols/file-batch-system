package com.example.batch.console.domain.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import com.example.batch.console.domain.notification.service.ConsoleWebhookService;
import com.example.batch.console.domain.notification.service.ConsoleWebhookService.CreateSubscriptionCommand;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.ops.CreateWebhookRequest;
import com.example.batch.console.web.request.ops.UpdateWebhookRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleWebhookControllerTest {

  private final ConsoleWebhookService webhookService = mock(ConsoleWebhookService.class);
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

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleWebhookController(
                    webhookService, responseFactory, requestMetadataResolver))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void shouldListWebhookSubscriptions() throws Exception {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setId(1L);
    entity.setTenantId("t1");
    entity.setName("job-events");
    entity.setCallbackUrl("https://callback.example/webhook");
    entity.setEventTypes("JOB-INSTANCE-UPDATED");
    entity.setEnabled(true);
    when(webhookService.listSubscriptions("t1")).thenReturn(List.of(entity));

    mockMvc
        .perform(get("/api/console/webhooks").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].name").value("job-events"))
        .andExpect(jsonPath("$.data[0].callbackUrl").value("https://callback.example/webhook"));
  }

  @Test
  void shouldCreateWebhookSubscription() throws Exception {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setId(1L);
    entity.setTenantId("t1");
    entity.setName("job-events");
    entity.setCallbackUrl("https://callback.example/webhook");
    entity.setEventTypes("JOB-INSTANCE-UPDATED");
    entity.setEnabled(true);
    when(webhookService.createSubscription(any(CreateSubscriptionCommand.class)))
        .thenReturn(entity);

    mockMvc
        .perform(
            post("/api/console/webhooks")
                .param("tenantId", "t1")
                .contentType(APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"job-events",
                      "callbackUrl":"https://callback.example/webhook",
                      "eventTypes":["job-instance-updated"],
                      "secret":"secret-1",
                      "enabled":true
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.name").value("job-events"))
        .andExpect(jsonPath("$.data.eventTypes").value("JOB-INSTANCE-UPDATED"));
  }

  @Test
  void shouldRestrictWebhookWritesToAdminOrTenantAdmin() throws Exception {
    assertThat(preAuthorize("create", String.class, CreateWebhookRequest.class))
        .isEqualTo("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')");
    assertThat(preAuthorize("update", String.class, Long.class, UpdateWebhookRequest.class))
        .isEqualTo("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')");
    assertThat(preAuthorize("delete", String.class, Long.class))
        .isEqualTo("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')");
    assertThat(preAuthorize("list", String.class)).contains("ROLE_TENANT_USER");
  }

  private String preAuthorize(String methodName, Class<?>... parameterTypes) throws Exception {
    return ConsoleWebhookController.class
        .getMethod(methodName, parameterTypes)
        .getAnnotation(PreAuthorize.class)
        .value();
  }
}
