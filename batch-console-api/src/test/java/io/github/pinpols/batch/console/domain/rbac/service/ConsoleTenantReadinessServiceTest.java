package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.rbac.ConsoleTenantReadinessService;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigReadinessView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigReadinessView;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionQueueRefView;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.TenantReadinessResponse;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.rbac.view.TenantRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 租户就绪自检:验证 template / channel / queue 三类阻断与警告判定。 */
@ExtendWith(MockitoExtension.class)
// LENIENT 原因：各用例只触发部分 Mapper，共享准备桩覆盖不同的就绪阻断分支。
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("租户就绪自检服务: 模板、通道与队列配置的阻断与警告判定")
class ConsoleTenantReadinessServiceTest {

  @Mock
  private TenantMapper tenantMapper;

  @Mock
  private FileTemplateConfigMapper templateMapper;

  @Mock
  private FileChannelConfigMapper channelMapper;

  @Mock
  private ResourceQueueMapper resourceQueueMapper;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @InjectMocks
  private ConsoleTenantReadinessService service;

  @BeforeEach
  void setUp() {
    when(tenantMapper.selectByTenantId("t1"))
        .thenReturn(new TenantRow(null, "t1", null, null, null, null, null, null));
    when(templateMapper.selectReadinessRows("t1")).thenReturn(List.of());
    when(channelMapper.selectReadinessRows("t1")).thenReturn(List.of());
    when(resourceQueueMapper.selectQueueCodes("t1")).thenReturn(List.of());
    when(jobDefinitionMapper.selectEnabledJobQueueRefs("t1")).thenReturn(List.of());
  }

  @Test
  @DisplayName("全部配置齐备时就绪通过, 既无阻断项也无警告项")
  void shouldBeReady_whenAllConfigComplete() {
    TenantReadinessResponse r = service.check("t1");
    assertThat(r.ready()).isTrue();
    assertThat(r.blocking()).isEmpty();
    assertThat(r.warnings()).isEmpty();
  }

  /** SEC-IDOR(S2):非全局角色对他租户跑就绪自检 → 守卫抛 FORBIDDEN,不落任何只读查询。 */
  @Test
  @DisplayName("跨租户自检被守卫拒绝, 不执行任何只读查询")
  void shouldDenyCrossTenantReadiness_beforeQuerying() {
    doThrow(BizException.of(ResultCode.FORBIDDEN, "error.tenant.mismatch"))
        .when(tenantGuard)
        .assertTenantAllowed("t-other");

    assertThatThrownBy(() -> service.check("t-other"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
    verify(tenantMapper, never()).selectByTenantId("t-other");
  }

  @Test
  @DisplayName("启用的导出模板缺少取数语句时判定阻断, 并给出可操作提示与文档引用")
  void shouldBlock_whenEnabledExportTemplateMissingQuerySql() {
    when(templateMapper.selectReadinessRows("t1"))
        .thenReturn(List.of(FileTemplateConfigReadinessView.builder()
            .templateCode("exp1")
            .templateType("EXPORT")
            .enabled(Boolean.TRUE)
            .fieldMappings("{\"a\":1}")
            .namingRule("n")
            .build()));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isFalse();
    assertThat(r.blocking()).hasSize(1);
    assertThat(r.blocking().get(0).item()).isEqualTo("template");
    assertThat(r.blocking().get(0).ref()).isEqualTo("exp1");
    // hint / docRef 是「报怎么补」引导:blocking 项必须给出可操作提示 + 文档引用。
    assertThat(r.blocking().get(0).hint())
        .contains("对应场景配置包的文件模板 Sheet")
        .contains("default_query_sql");
    assertThat(r.blocking().get(0).docRef()).contains("first-tenant-config-quickstart.md");
  }

  @Test
  @DisplayName("未启用的模板配置不完整时只告警不阻断")
  void shouldWarnNotBlock_whenDisabledTemplateIncomplete() {
    when(templateMapper.selectReadinessRows("t1"))
        .thenReturn(List.of(FileTemplateConfigReadinessView.builder()
            .templateCode("imp1")
            .templateType("IMPORT")
            .enabled(Boolean.FALSE)
            .build()));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isTrue();
    assertThat(r.blocking()).isEmpty();
    assertThat(r.warnings()).hasSize(1);
  }

  @Test
  @DisplayName("启用的通道配置体为空对象时判定阻断")
  void shouldBlock_whenEnabledChannelHasEmptyConfigJson() {
    when(channelMapper.selectReadinessRows("t1"))
        .thenReturn(List.of(FileChannelConfigReadinessView.builder()
            .channelCode("ch1")
            .channelType("SFTP")
            .authType("PASSWORD")
            .enabled(Boolean.TRUE)
            .configJson("{}")
            .targetEndpoint("sftp://x")
            .build()));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isFalse();
    assertThat(r.blocking())
        .extracting(TenantReadinessResponse.ReadinessItem::item)
        .contains("channel");
  }

  @Test
  @DisplayName("通道免认证时不判定阻断")
  void shouldNotBlock_whenChannelAuthNone() {
    when(channelMapper.selectReadinessRows("t1"))
        .thenReturn(List.of(FileChannelConfigReadinessView.builder()
            .channelCode("ch2")
            .channelType("LOCAL")
            .authType("NONE")
            .enabled(Boolean.TRUE)
            .configJson("{}")
            .build()));

    TenantReadinessResponse r = service.check("t1");
    assertThat(r.ready()).isTrue();
  }

  @Test
  @DisplayName("作业引用了不存在的物理队列时判定阻断并指向平台运维")
  void shouldBlock_whenJobReferencesMissingQueue() {
    when(resourceQueueMapper.selectQueueCodes("t1")).thenReturn(List.of("q-existing"));
    when(jobDefinitionMapper.selectEnabledJobQueueRefs("t1"))
        .thenReturn(List.of(new JobDefinitionQueueRefView("job1", "q-missing")));

    TenantReadinessResponse r = service.check("t1");

    assertThat(r.ready()).isFalse();
    assertThat(r.blocking()).hasSize(1);
    assertThat(r.blocking().get(0).item()).isEqualTo("queue");
    assertThat(r.blocking().get(0).ref()).isEqualTo("job1");
    assertThat(r.blocking().get(0).hint())
        .contains("平台运维")
        .contains("租户配置包不能创建队列")
        .doesNotContain("resource_queue sheet", "把作业 queue_code 改为")
        .contains("不能调整物理队列引用");
    assertThat(r.blocking().get(0).docRef()).contains("first-tenant-config-quickstart.md");
  }

  @Test
  @DisplayName("租户不存在时自检抛出业务异常")
  void shouldThrow_whenTenantNotFound() {
    when(tenantMapper.selectByTenantId("nope")).thenReturn(null);
    assertThatThrownBy(() -> service.check("nope")).isInstanceOf(BizException.class);
  }
}
