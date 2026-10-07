package io.github.pinpols.batch.console.application.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("实时事件票据服务: 票据签发与角色集合校验")
class SseTicketServiceTest {

  @Test
  @DisplayName("签发票据后校验时,正式角色集合应被完整保留")
  void issueAndValidate_preservesFormalRoles() {
    SseTicketStore store = mock(SseTicketStore.class);
    SseTicketService service = new SseTicketService(store);
    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

    String ticket = service.issue("alice", "tenant-a", Set.of("ROLE_TENANT_USER"));
    verify(store).save(anyString(), valueCaptor.capture(), any(Duration.class));
    when(store.consume(ticket)).thenReturn(valueCaptor.getValue());

    SseTicketService.TicketPayload payload = service.validate(ticket);
    assertThat(payload.authorities()).containsExactly("ROLE_TENANT_USER");
  }

  @Test
  @DisplayName("签发时传入历史遗留角色集合,应抛出非法参数异常")
  void issue_rejectsLegacyRole() {
    SseTicketService service = new SseTicketService(mock(SseTicketStore.class));

    assertThatThrownBy(() -> service.issue("alice", "tenant-a", Set.of("ROLE_USER")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported console role set");
  }
}
