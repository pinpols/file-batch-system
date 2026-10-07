package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateEntity;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * P0: ConsoleAdminMaintenanceController 热更新 + 状态序列化。
 *
 * <p>覆盖:GET 当前状态、PUT 部分字段更新、affectedServices 透传、AtomicReference 不可变替换、@AuditAction
 * 注解仍写审计(本测试不验证审计写入数据库, 由 AuditAspect 单测覆盖)。
 */
@DisplayName("维护模式管理接口:查询初始状态, 热更新部分字段并在关闭后恢复, 空服务列表归一为空数组")
class ConsoleAdminMaintenanceControllerTest {

  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private ConsoleMaintenanceProperties properties;
  private MaintenanceStateMapper mapper;
  private MaintenanceStateHolder stateHolder;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "ta", "tester", null, "127.0.0.1"));

    properties = new ConsoleMaintenanceProperties();
    mapper = mock(MaintenanceStateMapper.class);
    when(mapper.selectSingleton()).thenAnswer(invocation -> entityFromProperties());
    when(mapper.updateIfVersion(
            any(boolean.class), any(boolean.class), any(), any(), any(), any(), anyLong()))
        .thenReturn(1);
    stateHolder = new MaintenanceStateHolder(properties, mapper, new ObjectMapper());
    // PostConstruct 在 standalone setup 下需手动调
    ReflectionTestUtils.invokeMethod(stateHolder, "initFromProperties");

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleAdminMaintenanceController(
            stateHolder, responseFactory, requestMetadataResolver))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("初始查询:未开启维护时返回成功响应, 启用与只读标记均为假")
  void shouldReturnInitialDisabledState_whenQuerying() throws Exception {
    mockMvc
        .perform(get("/api/console/admin/system/maintenance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.readOnly").value(false));
  }

  @Test
  @DisplayName("热更新:部分字段更新后状态就地替换, 服务列表与更新时间一并回填")
  void shouldHotUpdateState_whenUpdating() throws Exception {
    String body = """
        {"enabled":true,"readOnly":true,"message":"DB 灰度中","etaAt":"2026-05-20T15:00:00Z",
         "affectedServices":["job-schedule","file-download"]}
        """;
    mockMvc
        .perform(put("/api/console/admin/system/maintenance")
            .contentType(APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.readOnly").value(true))
        .andExpect(jsonPath("$.data.message").value("DB 灰度中"))
        .andExpect(jsonPath("$.data.affectedServices[0]").value("job-schedule"))
        .andExpect(jsonPath("$.data.affectedServices[1]").value("file-download"))
        .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    // holder 中持有的状态已替换
    MaintenanceState current = stateHolder.current();
    assertThat(current.enabled()).isTrue();
    assertThat(current.readOnly()).isTrue();
    assertThat(current.message()).isEqualTo("DB 灰度中");
    assertThat(current.affectedServices()).containsExactly("job-schedule", "file-download");
    assertThat(current.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("关闭维护:仅传启用为假时状态复位, 查询结果同步为假")
  void shouldRestoreToDisabled_whenEnabledFalse() throws Exception {
    // 先开
    stateHolder.update(new MaintenanceState(
        true, false, "x", Instant.parse("2026-05-20T16:00:00Z"), List.of("a")));
    // 再关
    mockMvc
        .perform(put("/api/console/admin/system/maintenance")
            .contentType(APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));
    assertThat(stateHolder.current().enabled()).isFalse();
  }

  @Test
  @DisplayName("空列表归一:未提供受影响服务时返回空数组, 长度为 0")
  void shouldNormalizeToEmptyList_whenAffectedServicesAbsent() throws Exception {
    mockMvc
        .perform(put("/api/console/admin/system/maintenance")
            .contentType(APPLICATION_JSON)
            .content("{\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.affectedServices").isArray())
        .andExpect(jsonPath("$.data.affectedServices.length()").value(0));
  }

  private MaintenanceStateEntity entityFromProperties() {
    MaintenanceStateEntity entity = new MaintenanceStateEntity();
    entity.setEnabled(properties.isEnabled());
    entity.setReadOnly(properties.isReadOnly());
    entity.setMessage(properties.getMessage());
    entity.setEtaAt(properties.getEtaAt());
    entity.setAffectedServicesJson(new ObjectMapper()
        .valueToTree(
            properties.getAffectedServices() == null ? List.of() : properties.getAffectedServices())
        .toString());
    entity.setVersion(0L);
    return entity;
  }
}
