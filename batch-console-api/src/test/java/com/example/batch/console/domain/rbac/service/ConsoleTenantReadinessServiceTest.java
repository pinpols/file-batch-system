package com.example.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.file.mapper.FileChannelConfigMapper;
import com.example.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import com.example.batch.console.domain.job.mapper.JobDefinitionMapper;
import com.example.batch.console.domain.ops.mapper.ResourceQueueMapper;
import com.example.batch.console.domain.rbac.mapper.TenantMapper;
import com.example.batch.console.domain.rbac.web.response.TenantReadinessResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 租户就绪自检:验证 template / channel / queue 三类阻断与警告判定。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 各用例只触发部分 mapper,共享 setUp stub
class ConsoleTenantReadinessServiceTest {

  @Mock private TenantMapper tenantMapper;
  @Mock private FileTemplateConfigMapper templateMapper;
  @Mock private FileChannelConfigMapper channelMapper;
  @Mock private ResourceQueueMapper resourceQueueMapper;
  @Mock private JobDefinitionMapper jobDefinitionMapper;

  @InjectMocks private ConsoleTenantReadinessService service;

  @BeforeEach
  void setUp() {
    when(tenantMapper.selectByTenantId("t1")).thenReturn(Map.of("tenant_id", "t1"));
    when(templateMapper.selectReadinessRows("t1")).thenReturn(List.of());
    when(channelMapper.selectReadinessRows("t1")).thenReturn(List.of());
    when(resourceQueueMapper.selectQueueCodes("t1")).thenReturn(List.of());
    when(jobDefinitionMapper.selectEnabledJobQueueRefs("t1")).thenReturn(List.of());
  }

  private Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void shouldBeReady_whenAllConfigComplete() {
    TenantReadinessResponse r = service.check("t1");
    assertThat(r.ready()).isTrue();
    assertThat(r.blocking()).isEmpty();
    assertThat(r.warnings()).isEmpty();
  }

  @Test
  void shouldBlock_whenEnabledExportTemplateMissingQuerySql() {
    when(templateMapper.selectReadinessRows("t1"))
        .thenReturn(
            List.of(
                row(
                    "template_code", "exp1",
                    "template_type", "EXPORT",
                    "enabled", Boolean.TRUE,
                    "default_query_sql", null,
                    "field_mappings", "{\"a\":1}",
                    "naming_rule", "n")));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isFalse();
    assertThat(r.blocking()).hasSize(1);
    assertThat(r.blocking().get(0).item()).isEqualTo("template");
    assertThat(r.blocking().get(0).ref()).isEqualTo("exp1");
    // hint / docRef 是「报怎么补」引导:blocking 项必须给出可操作提示 + 文档引用。
    assertThat(r.blocking().get(0).hint())
        .contains("file_template_config")
        .contains("default_query_sql");
    assertThat(r.blocking().get(0).docRef()).contains("first-tenant-config-quickstart.md");
  }

  @Test
  void shouldWarnNotBlock_whenDisabledTemplateIncomplete() {
    when(templateMapper.selectReadinessRows("t1"))
        .thenReturn(
            List.of(
                row(
                    "template_code",
                    "imp1",
                    "template_type",
                    "IMPORT",
                    "enabled",
                    Boolean.FALSE,
                    "default_query_sql",
                    null,
                    "field_mappings",
                    null,
                    "naming_rule",
                    null)));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isTrue();
    assertThat(r.blocking()).isEmpty();
    assertThat(r.warnings()).hasSize(1);
  }

  @Test
  void shouldBlock_whenEnabledChannelHasEmptyConfigJson() {
    when(channelMapper.selectReadinessRows("t1"))
        .thenReturn(
            List.of(
                row(
                    "channel_code", "ch1",
                    "channel_type", "SFTP",
                    "auth_type", "PASSWORD",
                    "enabled", Boolean.TRUE,
                    "config_json", "{}",
                    "target_endpoint", "sftp://x")));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isFalse();
    assertThat(r.blocking())
        .extracting(TenantReadinessResponse.ReadinessItem::item)
        .contains("channel");
  }

  @Test
  void shouldNotBlock_whenChannelAuthNone() {
    when(channelMapper.selectReadinessRows("t1"))
        .thenReturn(
            List.of(
                row(
                    "channel_code", "ch2",
                    "channel_type", "LOCAL",
                    "auth_type", "NONE",
                    "enabled", Boolean.TRUE,
                    "config_json", "{}",
                    "target_endpoint", null)));

    TenantReadinessResponse r = service.check("t1");
    assertThat(r.ready()).isTrue();
  }

  @Test
  void shouldBlock_whenJobReferencesMissingQueue() {
    when(resourceQueueMapper.selectQueueCodes("t1")).thenReturn(List.of("q-existing"));
    when(jobDefinitionMapper.selectEnabledJobQueueRefs("t1"))
        .thenReturn(List.of(row("job_code", "job1", "queue_code", "q-missing")));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isFalse();
    assertThat(r.blocking()).hasSize(1);
    assertThat(r.blocking().get(0).item()).isEqualTo("queue");
    assertThat(r.blocking().get(0).ref()).isEqualTo("job1");
    assertThat(r.blocking().get(0).hint()).contains("resource_queue").contains("q-missing");
    assertThat(r.blocking().get(0).docRef()).contains("first-tenant-config-quickstart.md");
  }

  @Test
  void shouldThrow_whenTenantNotFound() {
    when(tenantMapper.selectByTenantId("nope")).thenReturn(null);
    assertThatThrownBy(() -> service.check("nope")).isInstanceOf(BizException.class);
  }
}
