package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileChannelQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FilePipelineStepQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FilePipelineStepRunQuery;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineStepRunQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

@DisplayName("文件配置查询: Mapper 结果投影为稳定响应类型")
class ConsoleFileConfigurationProjectionTest {

  private FileChannelConfigMapper channelMapper;
  private FileTemplateConfigMapper templateMapper;
  private FilePipelineStepRunMapper stepRunMapper;
  private ConsoleFileQueryService service;

  @BeforeEach
  void setUp() {
    channelMapper = mock(FileChannelConfigMapper.class);
    templateMapper = mock(FileTemplateConfigMapper.class);
    stepRunMapper = mock(FilePipelineStepRunMapper.class);
    ConsoleFileQueryMappers mappers = new ConsoleFileQueryMappers(
        null, null, null, null, stepRunMapper, null, channelMapper, templateMapper);
    TenantIdResolver tenantResolver = ignored -> "tenant-a";
    service = new ConsoleFileQueryService(
        tenantResolver,
        mappers,
        mock(BatchSecurityProperties.class),
        mock(LocalizedErrorRenderer.class),
        mock(ConsoleOrchestratorPort.class),
        mock(BatchDateTimeSupport.class));
  }

  @Test
  @DisplayName("渠道和模板查询使用具名投影映射并保留响应标识")
  void shouldProjectChannelAndTemplateRows() {
    when(channelMapper.selectByQuery(ArgumentMatchers.argThat(
            parameters -> parameters != null && parameters.tenantId() != null)))
        .thenReturn(List.of(FileChannelConfigQueryView.builder()
            .id(11L)
            .tenantId("tenant-a")
            .channelCode("SFTP")
            .build()));
    when(channelMapper.countByQuery(anyString(), any(), any(), any())).thenReturn(1L);
    when(templateMapper.selectByQuery(any()))
        .thenReturn(List.of(FileTemplateConfigQueryView.builder()
            .id(12L)
            .tenantId("tenant-a")
            .templateCode("IMPORT_CSV")
            .build()));
    when(templateMapper.countByQuery(any())).thenReturn(1L);

    var channels = service.fileChannels(new FileChannelQueryRequest());
    var templates = service.fileTemplates(new FileTemplateQueryRequest());

    assertThat(channels.items()).singleElement().extracting("channelCode").isEqualTo("SFTP");
    assertThat(templates.items())
        .singleElement()
        .extracting("templateCode")
        .isEqualTo("IMPORT_CSV");
  }

  @Test
  @DisplayName("文件流水线步骤摘要在响应中递归遮罩凭据字段")
  void shouldMaskCredentialsInPipelineStepSummaries() {
    when(stepRunMapper.selectByQuery(any(FilePipelineStepRunQuery.class)))
        .thenReturn(List.of(FilePipelineStepRunQueryView.builder()
            .id(1L)
            .pipelineInstanceId(12L)
            .stepCode("LOAD")
            .stageCode("LOAD")
            .inputSummary("{\"source\":\"upload.csv\",\"auth\":{\"clientSecret\":\"raw\"}}")
            .outputSummary("{\"rows\":5,\"auth\":{\"password\":\"raw\"}}")
            .build()));
    when(stepRunMapper.countByQuery(any(FilePipelineStepRunQuery.class))).thenReturn(1L);

    FilePipelineStepQueryRequest request = new FilePipelineStepQueryRequest();
    request.setTenantId("tenant-a");
    var response = service.filePipelineSteps(request).items().getFirst();

    assertThat(response.inputSummary())
        .contains("&quot;source&quot;:&quot;upload.csv&quot;")
        .contains("&quot;clientSecret&quot;:&quot;****&quot;")
        .doesNotContain("raw");
    assertThat(response.outputSummary())
        .contains("&quot;rows&quot;:5")
        .contains("&quot;password&quot;:&quot;****&quot;")
        .doesNotContain("raw");
  }
}
