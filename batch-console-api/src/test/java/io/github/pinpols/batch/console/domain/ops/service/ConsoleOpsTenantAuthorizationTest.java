package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.entity.AtomicTaskConfigEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.AtomicTaskConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.JobTaskMapper;
import io.github.pinpols.batch.console.domain.ops.param.AtomicTaskConfigCreateParam;
import io.github.pinpols.batch.console.domain.ops.view.JobTaskHeartbeatView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("运维应用服务直接入口的租户隔离")
class ConsoleOpsTenantAuthorizationTest {
  @Mock
  private AtomicTaskConfigMapper configMapper;

  @Mock
  private JobTaskMapper taskMapper;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("租户管理员不能通过服务入口查询或创建其他租户数据")
  void shouldRejectOtherTenantBeforeAccessingMappers() {
    authenticate("tenant-a", "ROLE_TENANT_ADMIN");
    var config = configService();
    var heartbeat = heartbeatService();

    assertForbidden(() -> config.listByTaskType("tenant-b", "sql"));
    assertForbidden(
        () -> config.create("tenant-b", "sql", "daily", Map.of("sql", "select 1"), "alice"));
    assertForbidden(() -> heartbeat.getHeartbeatDetails("tenant-b", 42L));
    verifyNoInteractions(configMapper, taskMapper);
  }

  @Test
  @DisplayName("租户管理员访问本租户时使用解析后的租户查询和写入")
  void shouldAllowOwnTenantAndUseResolvedTenantForAllPersistence() {
    authenticate("tenant-a", "ROLE_TENANT_ADMIN");
    var config = configService();
    var heartbeat = heartbeatService();
    when(configMapper.insertAtomicTaskConfig(any())).thenAnswer(call -> {
      AtomicTaskConfigCreateParam param = call.getArgument(0);
      param.setId(42L);
      return 1;
    });
    when(configMapper.selectByTenantAndId("tenant-a", 42L))
        .thenReturn(new AtomicTaskConfigEntity());
    when(taskMapper.selectHeartbeatByTenantAndId("tenant-a", 42L))
        .thenReturn(new JobTaskHeartbeatView());

    config.listByTaskType(" tenant-a ", "sql");
    config.create(" tenant-a ", "sql", "daily", Map.of("sql", "select 1"), "alice");
    heartbeat.getHeartbeatDetails(" tenant-a ", 42L);

    verify(configMapper).selectByTenantAndTaskType("tenant-a", "sql");
    var param = ArgumentCaptor.forClass(AtomicTaskConfigCreateParam.class);
    verify(configMapper).insertAtomicTaskConfig(param.capture());
    assertThat(param.getValue().getTenantId()).isEqualTo("tenant-a");
    verify(taskMapper).selectHeartbeatByTenantAndId("tenant-a", 42L);
  }

  @Test
  @DisplayName("平台管理员可通过服务入口访问明确指定的目标租户")
  void shouldAllowGlobalAdministratorToAccessSpecifiedTenant() {
    authenticate("system", "ROLE_ADMIN");
    when(taskMapper.selectHeartbeatByTenantAndId("tenant-b", 42L))
        .thenReturn(new JobTaskHeartbeatView());

    configService().listByTaskType("tenant-b", "sql");
    heartbeatService().getHeartbeatDetails("tenant-b", 42L);

    verify(configMapper).selectByTenantAndTaskType("tenant-b", "sql");
    verify(taskMapper).selectHeartbeatByTenantAndId("tenant-b", 42L);
  }

  private ConsoleAtomicTaskConfigService configService() {
    return new ConsoleAtomicTaskConfigService(
        configMapper, new ConsoleAtomicTaskTypeSchemaService(), new ObjectMapper(), guard());
  }

  private ConsoleTaskHeartbeatService heartbeatService() {
    return new ConsoleTaskHeartbeatService(taskMapper, new ObjectMapper(), guard());
  }

  private ConsoleTenantGuard guard() {
    return new ConsoleTenantGuard(metadataResolver);
  }

  private static void authenticate(String tenant, String role) {
    var principal = new ConsolePrincipal("alice", tenant, Set.of(role));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, "ignored"));
  }

  private static void assertForbidden(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }
}
