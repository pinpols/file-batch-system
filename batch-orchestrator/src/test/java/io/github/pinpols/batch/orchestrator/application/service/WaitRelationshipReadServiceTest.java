package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipQuery;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import io.github.pinpols.batch.orchestrator.mapper.WaitRelationshipMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("等待关系服务的稳定游标分页")
class WaitRelationshipReadServiceTest {
  @Mock
  private WaitRelationshipMapper mapper;

  @Mock
  private WaitRelationshipSanitizer sanitizer;

  @InjectMocks
  private WaitRelationshipReadService service;

  @Test
  @DisplayName("存在后续记录时返回最后可见游标")
  void shouldReturnLastVisibleKeyOnlyWhenMoreRowsExist() {
    WaitRelationshipQuery query =
        new WaitRelationshipQuery("tenant-a", null, null, null, null, null, null, 2);
    WaitRelationshipRow first = row("CALENDAR", "1");
    WaitRelationshipRow second = row("JOB", "9007199254740993");
    WaitRelationshipRow third = row("READINESS", "8");
    when(mapper.selectPage(query, 3)).thenReturn(List.of(first, second, third));
    var page = service.list(query);
    verify(mapper).setTenantContext("tenant-a");
    assertThat(page.items()).containsExactly(first, second);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursorType()).isEqualTo("JOB");
    assertThat(page.nextCursorId()).isEqualTo("9007199254740993");
  }

  @Test
  @DisplayName("到达末页不返回后续游标")
  void shouldNotPromiseAContinuationAtEnd() {
    WaitRelationshipQuery query =
        new WaitRelationshipQuery("tenant-a", "JOB", null, null, null, "JOB", "2", null);
    when(mapper.selectPage(query, 101)).thenReturn(List.of(row("JOB", "3")));
    var page = service.list(query);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursorType()).isNull();
    assertThat(page.nextCursorId()).isNull();
  }

  private static WaitRelationshipRow row(String type, String id) {
    WaitRelationshipRow row = new WaitRelationshipRow();
    row.setWaitType(type);
    row.setId(id);
    return row;
  }
}
