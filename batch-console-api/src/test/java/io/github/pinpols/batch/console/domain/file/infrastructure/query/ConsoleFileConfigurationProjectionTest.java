package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileChannelQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件配置查询: Mapper 结果投影为稳定响应类型")
class ConsoleFileConfigurationProjectionTest {

  private FileChannelConfigMapper channelMapper;
  private FileTemplateConfigMapper templateMapper;
  private ConsoleFileQueryService service;

  @BeforeEach
  void setUp() {
    channelMapper = mock(FileChannelConfigMapper.class);
    templateMapper = mock(FileTemplateConfigMapper.class);
    ConsoleFileQueryMappers mappers = new ConsoleFileQueryMappers(
        null, null, null, null, null, null, channelMapper, templateMapper);
    TenantIdResolver tenantResolver = ignored -> "tenant-a";
    service = new ConsoleFileQueryService(
        tenantResolver,
        mappers,
        mock(BatchSecurityProperties.class),
        mock(LocalizedErrorRenderer.class),
        mock(ConsoleOrchestratorPort.class));
  }

  @Test
  @DisplayName("渠道和模板查询使用具名投影映射并保留响应标识")
  void shouldProjectChannelAndTemplateRows() {
    when(channelMapper.selectByQuery(anyString(), any(), any(), any(), any()))
        .thenReturn(List.of(Map.of("id", 11L, "tenant_id", "tenant-a", "channel_code", "SFTP")));
    when(channelMapper.countByQuery(anyString(), any(), any(), any())).thenReturn(1L);
    when(templateMapper.selectByQuery(any()))
        .thenReturn(
            List.of(Map.of("id", 12L, "tenant_id", "tenant-a", "template_code", "IMPORT_CSV")));
    when(templateMapper.countByQuery(any())).thenReturn(1L);

    var channels = service.fileChannels(new FileChannelQueryRequest());
    var templates = service.fileTemplates(new FileTemplateQueryRequest());

    assertThat(channels.items()).singleElement().extracting("channelCode").isEqualTo("SFTP");
    assertThat(templates.items())
        .singleElement()
        .extracting("templateCode")
        .isEqualTo("IMPORT_CSV");
  }
}
