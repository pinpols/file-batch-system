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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SseTicketServiceTest {

  @Test
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
  void issue_rejectsLegacyRole() {
    SseTicketService service = new SseTicketService(mock(SseTicketStore.class));

    assertThatThrownBy(() -> service.issue("alice", "tenant-a", Set.of("ROLE_USER")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported console role set");
  }
}
