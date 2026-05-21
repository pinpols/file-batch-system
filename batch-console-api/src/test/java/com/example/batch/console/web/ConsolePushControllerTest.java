package com.example.batch.console.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.ResponseMeta;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.service.ConsolePushSubscriptionService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleApiExceptionHandler;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.push.ConsolePushSubscribeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** P2: ConsolePushController VAPID 公开 + subscribe/unsubscribe 透传 endpoint+keys。 */
class ConsolePushControllerTest {

  private final ConsolePushSubscriptionService subscriptionService =
      mock(ConsolePushSubscriptionService.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        new ConsoleApiExceptionHandler(responseFactory, new BatchSecurityProperties());
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "ta", "tester", null, "127.0.0.1"));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsolePushController(
                    subscriptionService, responseFactory, requestMetadataResolver))
            .setControllerAdvice(exceptionHandler)
            .setValidator(validator)
            .build();
  }

  @Test
  void vapidPublicKeyShouldReturnConfiguredKey() throws Exception {
    when(subscriptionService.vapidPublicKey()).thenReturn("BPK-xxx");
    mockMvc
        .perform(get("/api/console/push/vapid-public-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.publicKey").value("BPK-xxx"));
  }

  @Test
  void subscribeShouldReturn201AndForwardToService() throws Exception {
    mockMvc
        .perform(
            post("/api/console/push/subscribe")
                .param("tenantId", "ta")
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"endpoint\":\"https://push.example/x\",\"keys\":{\"p256dh\":\"P256\",\"auth\":\"AUTH\"}}"))
        .andExpect(status().isCreated());
    verify(subscriptionService)
        .subscribe(eq("ta"), eq("tester"), any(ConsolePushSubscribeRequest.class), any());
  }

  @Test
  void unsubscribeShouldReturn204AndForwardEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/console/push/unsubscribe")
                .param("tenantId", "ta")
                .contentType(APPLICATION_JSON)
                .content("{\"endpoint\":\"https://push.example/x\"}"))
        .andExpect(status().isNoContent());
    verify(subscriptionService).unsubscribe("ta", "tester", "https://push.example/x");
  }
}
