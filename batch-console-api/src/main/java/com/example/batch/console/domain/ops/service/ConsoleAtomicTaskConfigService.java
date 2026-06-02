package com.example.batch.console.domain.ops.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.security.SensitiveDataValidator;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema.ParamSpec;
import com.example.batch.console.domain.ops.entity.AtomicTaskConfigEntity;
import com.example.batch.console.domain.ops.mapper.AtomicTaskConfigMapper;
import com.example.batch.console.domain.ops.param.AtomicTaskConfigCreateParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R3-5 / Round-1 TOP-8 — atomic 节点配置写库 service。
 *
 * <p>承接 console FE 2-B 工作流编辑器中"原子节点配置可保存"诉求(原仅展示 schema 不存)。 创建链:
 *
 * <ol>
 *   <li>{@link ConsoleAtomicTaskTypeSchemaService} 拉 schema → taskType 必须命中四类内置(sql / stored_proc /
 *       shell / http);
 *   <li>parameters Map key 必须落 schema {@code parameters[].name} 集合;必填字段不可缺;
 *   <li>{@link SensitiveDataValidator} 拒入凭据关键字(#242);
 *   <li>同事务 INSERT,UNIQUE(tenant_id, task_type, name) DB 兜底重复名。
 * </ol>
 *
 * <p>本 service 只调用 {@link ConsoleAtomicTaskTypeSchemaService},不修改 schema 目录(单一权威源)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleAtomicTaskConfigService {

  private final AtomicTaskConfigMapper mapper;
  private final ConsoleAtomicTaskTypeSchemaService schemaService;
  private final ObjectMapper objectMapper;

  /** 列出本租户某 taskType 已保存的所有配置(created_at 倒序)。 */
  public List<AtomicTaskConfigEntity> listByTaskType(String tenantId, String taskType) {
    String normalizedType = requireTaskType(taskType);
    // 不存在的 taskType 直接 fail-fast,避免让 FE 误以为"空列表 = 没数据"
    locateSchema(normalizedType);
    return mapper.selectByTenantAndTaskType(tenantId, normalizedType);
  }

  /**
   * 创建一条 atomic 节点配置。CLAUDE.md §Java #4:@Transactional 在 Service。
   *
   * @param tenantId 已经 ConsoleTenantGuard.resolveTenant 解析过的 tenantId
   * @param taskType 内置原子 taskType
   * @param name 同租户同 taskType 内唯一的配置名(本方法不做长度截断,DB VARCHAR(128) 兜底)
   * @param parameters 节点参数(key 必须落 schema)
   * @param createdBy 创建人(可空)
   * @return 持久化后的 entity(含回写的自增 id)
   */
  @Transactional
  public AtomicTaskConfigEntity create(
      String tenantId,
      String taskType,
      String name,
      Map<String, Object> parameters,
      String createdBy) {
    String normalizedType = requireTaskType(taskType);
    String normalizedName = requireName(name);
    AtomicTaskTypeSchema schema = locateSchema(normalizedType);

    Map<String, Object> safeParams = parameters == null ? Map.of() : parameters;
    // 1) 凭据字段静态拒入(#242 SensitiveDataValidator)
    SensitiveDataValidator.rejectIfContainsSensitiveKeys(
        safeParams, "atomic_task_config." + normalizedType + ".parameters");
    // 2) parameters key 必须落 schema.parameters[].name;必填字段不可缺
    validateAgainstSchema(schema, safeParams);

    String parametersJson;
    try {
      parametersJson = objectMapper.writeValueAsString(safeParams);
    } catch (JsonProcessingException ex) {
      // 业务参数序列化失败属于编程错误 / 不合规 JSON 输入,转 BizException 由 i18n 兜底
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.atomic_task_config.parameters_not_serializable",
          ex.getOriginalMessage());
    }

    AtomicTaskConfigCreateParam param = new AtomicTaskConfigCreateParam();
    param.setTenantId(tenantId);
    param.setTaskType(normalizedType);
    param.setName(normalizedName);
    param.setParametersJson(parametersJson);
    param.setCreatedBy(createdBy);
    mapper.insertAtomicTaskConfig(param);

    log.info(
        "atomic_task_config created tenant={} taskType={} name={} id={}",
        tenantId,
        normalizedType,
        normalizedName,
        param.getId());

    AtomicTaskConfigEntity persisted = mapper.selectByTenantAndId(tenantId, param.getId());
    if (persisted == null) {
      // 极少触发(刚 INSERT 同事务读不到 = 数据源故障),给出明确诊断而不是 NPE
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.atomic_task_config.persist_readback_failed",
          param.getId());
    }
    return persisted;
  }

  private AtomicTaskTypeSchema locateSchema(String taskType) {
    return schemaService.schema().stream()
        .filter(s -> s.taskType().equals(taskType))
        .findFirst()
        .orElseThrow(
            () ->
                BizException.of(
                    ResultCode.INVALID_ARGUMENT,
                    "error.atomic_task_config.task_type_unknown",
                    taskType));
  }

  private void validateAgainstSchema(AtomicTaskTypeSchema schema, Map<String, Object> parameters) {
    Set<String> allowed =
        schema.parameters().stream().map(ParamSpec::name).collect(Collectors.toSet());
    Set<String> extraneous = new TreeSet<>(parameters.keySet());
    extraneous.removeAll(allowed);
    if (!extraneous.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.atomic_task_config.parameters_unknown_key",
          schema.taskType(),
          String.join(",", extraneous));
    }
    Set<String> requiredMissing = new TreeSet<>();
    for (ParamSpec spec : schema.parameters()) {
      if (!spec.required()) {
        continue;
      }
      Object value = parameters.get(spec.name());
      if (value == null
          || (value instanceof String strValue && strValue.isBlank())
          || (value instanceof Map<?, ?> mapValue && mapValue.isEmpty())) {
        requiredMissing.add(spec.name());
      }
    }
    if (!requiredMissing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.atomic_task_config.parameters_required_missing",
          schema.taskType(),
          String.join(",", requiredMissing));
    }
  }

  private static String requireTaskType(String taskType) {
    if (taskType == null || taskType.isBlank()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.atomic_task_config.task_type_required");
    }
    return taskType.trim();
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.atomic_task_config.name_required");
    }
    return name.trim();
  }
}
