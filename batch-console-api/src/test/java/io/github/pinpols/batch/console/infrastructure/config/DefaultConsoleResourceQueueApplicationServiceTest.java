package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueCreateRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueListRequest;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.ops.query.ResourceQueueQuery;
import io.github.pinpols.batch.console.domain.ops.view.ResourceQueueRow;
import io.github.pinpols.batch.console.domain.param.ResourceQueueInsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("资源队列规范引用的一致性")
class DefaultConsoleResourceQueueApplicationServiceTest {

  @Mock
  private ResourceQueueMapper mapper;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @InjectMocks
  private DefaultConsoleResourceQueueApplicationService service;

  @Captor
  private ArgumentCaptor<ResourceQueueInsertParam> paramsCaptor;

  @BeforeEach
  void setUp() {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
  }

  @DisplayName("持久化队列编码与作业引用一致")
  @Test
  void shouldStoreSameQueueCodeAsJobReferences() {
    ResourceQueueCreateRequest request = request("Dispatch-Queue");
    when(mapper.selectByUniqueKey(eq("ta"), anyString())).thenReturn(null);
    doAnswer(invocation -> {
          ResourceQueueInsertParam params = invocation.getArgument(0);
          params.setId(9L);
          return 1;
        })
        .when(mapper)
        .insert(any(ResourceQueueInsertParam.class));
    when(mapper.selectById("ta", 9L))
        .thenReturn(new ResourceQueueRow(
            9L,
            "ta",
            "dispatch_queue",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(service.create(request).queueCode()).isEqualTo("dispatch_queue");

    verify(mapper).selectByUniqueKey("ta", "dispatch_queue");
    verify(mapper).insert(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().getQueueCode()).isEqualTo("dispatch_queue");
  }

  @DisplayName("拒绝已有规范队列的别名")
  @Test
  void shouldRejectAliasOfExistingCanonicalQueue() {
    when(mapper.selectByUniqueKey(eq("ta"), anyString()))
        .thenReturn(new ResourceQueueRow(
            9L, "ta", null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null));

    assertThatThrownBy(() -> service.create(request("Dispatch-Queue")))
        .isInstanceOf(BizException.class);

    verify(mapper).selectByUniqueKey("ta", "dispatch_queue");
    verify(mapper, never()).insert(any(ResourceQueueInsertParam.class));
  }

  @DisplayName("查询使用规范队列引用")
  @Test
  void shouldQueryQueueUsingCanonicalReference() {
    ResourceQueueListRequest request =
        new ResourceQueueListRequest("ta", "Dispatch-Queue", null, true, 1, 20);
    ResourceQueueQuery expected =
        new ResourceQueueQuery("ta", "dispatch_queue", null, true, new PageRequest(1, 20));

    service.list(request);

    verify(mapper).countByQuery(expected);
    verify(mapper).selectByQuery(expected);
  }

  private static ResourceQueueCreateRequest request(String code) {
    ResourceQueueCreateRequest request = new ResourceQueueCreateRequest();
    request.setTenantId("ta");
    request.setQueueCode(code);
    request.setQueueType("MIXED");
    request.setMaxRunningJobs(1);
    return request;
  }
}
