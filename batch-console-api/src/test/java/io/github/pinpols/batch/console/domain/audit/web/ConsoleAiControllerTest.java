package io.github.pinpols.batch.console.domain.audit.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiApplicationService;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiChatResponse;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConsoleAiControllerTest {

  private final ConsoleAiApplicationService applicationService =
      mock(ConsoleAiApplicationService.class);
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

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleAiController(
            applicationService,
            responseFactory,
            mock(ConsoleAiAuthorizationService.class),
            requestMetadataResolver,
            new ConsoleAiProperties()))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  void shouldReturn400WhenIdempotencyHeaderMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/console/ai/chat/stream").contentType(APPLICATION_JSON).content("""
                    {"tenantId":"t1","prompt":"列出今日失败的作业"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

    verifyNoInteractions(applicationService);
  }

  @Test
  void shouldReturn400WhenPromptIsBlank() throws Exception {
    mockMvc
        .perform(post("/api/console/ai/chat/stream")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-001")
            .contentType(APPLICATION_JSON)
            .content("""
                    {"tenantId":"t1","prompt":""}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    verifyNoInteractions(applicationService);
  }

  @Test
  void shouldNotExposeLegacyJsonChatEndpoint() throws Exception {
    mockMvc
        .perform(post("/api/console/ai/chat")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-old")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"t1","prompt":"查询失败作业"}
                """))
        .andExpect(status().isNotFound());
    verifyNoInteractions(applicationService);
  }

  @Test
  void shouldStreamDeltasAndFinalAuditedAnswer() throws Exception {
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata(
            "req-stream", "trace-stream", "t1", "operator-a", null, null));
    AiChatResponse chatResponse = new AiChatResponse();
    chatResponse.setRequestId("req-stream");
    chatResponse.setAnswer("hello world");
    when(applicationService.chatStream(any(), anyString(), any(), any())).thenAnswer(invocation -> {
      ConsoleAiApplicationService.StreamObserver observer = invocation.getArgument(3);
      observer.onDelta("hello ");
      observer.onDelta("world");
      return chatResponse;
    });

    MvcResult pending = mockMvc
        .perform(post("/api/console/ai/chat/stream")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-stream")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"t1","prompt":"explain job failure"}
                """))
        .andExpect(request().asyncStarted())
        .andReturn();

    String stream = mockMvc
        .perform(MockMvcRequestBuilders.asyncDispatch(pending))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
    org.junit.jupiter.api.Assertions.assertTrue(stream.contains("event:started"));
    org.junit.jupiter.api.Assertions.assertTrue(stream.contains("event:delta"));
    org.junit.jupiter.api.Assertions.assertTrue(stream.contains("event:completed"));
    org.junit.jupiter.api.Assertions.assertTrue(stream.contains("hello world"));
  }

  @Test
  void shouldRejectCrossOperatorCancellation() throws Exception {
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-stream", "trace-stream", "t1", "owner", null, null));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(applicationService.chatStream(any(), anyString(), any(), any())).thenAnswer(invocation -> {
      entered.countDown();
      release.await(2, TimeUnit.SECONDS);
      return new AiChatResponse();
    });
    MvcResult pending = mockMvc
        .perform(post("/api/console/ai/chat/stream")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-stream")
            .contentType(APPLICATION_JSON)
            .content("""
                {"tenantId":"t1","prompt":"explain job failure"}
                """))
        .andExpect(request().asyncStarted())
        .andReturn();
    org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-cancel", "trace-cancel", "t1", "other", null, null));
    try {
      mockMvc
          .perform(post("/api/console/ai/chat/stream/req-stream/cancel")
              .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cancel"))
          .andExpect(status().isNotFound());
    } finally {
      release.countDown();
      mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(pending));
    }
  }

  @Test
  void shouldReturnOwnerScopedConversationPage() throws Exception {
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "tenant-a", "operator-a", null, null));
    when(applicationService.conversationPage("tenant-a", "operator-a", "cursor-1", 2))
        .thenReturn(PageResponse.cursor(List.of(), 2, null));

    mockMvc
        .perform(get("/api/console/ai/conversations/page")
            .param("cursor", "cursor-1")
            .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pageSize").value(2))
        .andExpect(jsonPath("$.data.hasMore").value(false));

    verify(applicationService).conversationPage("tenant-a", "operator-a", "cursor-1", 2);
  }
}
