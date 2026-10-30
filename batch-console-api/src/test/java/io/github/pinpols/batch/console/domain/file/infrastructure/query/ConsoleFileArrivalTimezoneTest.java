package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.contract.query.FileChainQueryRequest;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.query.FileRecordQuery;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

@DisplayName("今日到达查询:业务时区自然日")
class ConsoleFileArrivalTimezoneTest {
  @ParameterizedTest
  @CsvSource({
    "2026-03-08T12:00:00Z, 2026-03-08T05:00:00Z, 2026-03-09T03:59:59.999999Z",
    "2026-11-01T12:00:00Z, 2026-11-01T04:00:00Z, 2026-11-02T04:59:59.999999Z"
  })
  @DisplayName("夏令时切换日以当地两个午夜构造创建时间边界")
  void shouldUseLocalMidnightsOnDaylightSavingTransition(
      String now, String expectedStart, String expectedEnd) {
    BatchTimezoneProperties properties = new BatchTimezoneProperties();
    properties.setDefaultZone("America/New_York");
    BatchDateTimeSupport clock = new BatchDateTimeSupport(
        Clock.fixed(Instant.parse(now), ZoneOffset.UTC), new BatchTimezoneProvider(properties));
    FileRecordMapper mapper = mock(FileRecordMapper.class);
    when(mapper.selectByQuery(any())).thenReturn(List.of());
    ConsoleFileQueryMappers mappers =
        new ConsoleFileQueryMappers(mapper, null, null, null, null, null, null, null);
    TenantIdResolver tenantResolver = ignored -> "tenant-a";
    ConsoleFileQueryService service = new ConsoleFileQueryService(
        tenantResolver,
        mappers,
        mock(BatchSecurityProperties.class),
        mock(LocalizedErrorRenderer.class),
        mock(ConsoleOrchestratorPort.class),
        clock);
    FileChainQueryRequest request = new FileChainQueryRequest();
    request.setArrivalToday(true);
    service.fileChains(request);
    ArgumentCaptor<FileRecordQuery> captured = ArgumentCaptor.forClass(FileRecordQuery.class);
    verify(mapper).selectByQuery(captured.capture());
    assertThat(captured.getValue().fromTime()).isEqualTo(Instant.parse(expectedStart));
    assertThat(captured.getValue().toTime()).isEqualTo(Instant.parse(expectedEnd));
    verify(mapper).countByQuery(captured.getValue());
  }
}
