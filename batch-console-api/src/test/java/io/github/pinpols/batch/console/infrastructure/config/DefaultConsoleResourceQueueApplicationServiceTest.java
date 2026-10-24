package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.ops.query.ResourceQueueQuery;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueListRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueCreateRequest;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.Map;
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
  private ArgumentCaptor<Map<String, Object>> paramsCaptor;

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
          Map<String, Object> params = invocation.getArgument(0);
          params.put("id", 9L);
          return 1;
        })
        .when(mapper)
        .insert(anyMap());
    when(mapper.selectById("ta", 9L)).thenReturn(Map.of("id", 9L, "queue_code", "dispatch_queue"));

    assertThat(service.create(request).queueCode()).isEqualTo("dispatch_queue");

    verify(mapper).selectByUniqueKey("ta", "dispatch_queue");
    verify(mapper).insert(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue()).containsEntry("queue_code", "dispatch_queue");
  }

  @DisplayName("拒绝已有规范队列的别名")
  @Test
  void shouldRejectAliasOfExistingCanonicalQueue() {
    when(mapper.selectByUniqueKey(eq("ta"), anyString())).thenReturn(Map.of("id", 9L));

    assertThatThrownBy(() -> service.create(request("Dispatch-Queue")))
        .isInstanceOf(BizException.class);

    verify(mapper).selectByUniqueKey("ta", "dispatch_queue");
    verify(mapper, never()).insert(anyMap());
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
