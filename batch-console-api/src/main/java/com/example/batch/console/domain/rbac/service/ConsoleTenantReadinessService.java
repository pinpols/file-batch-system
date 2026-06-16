package com.example.batch.console.domain.rbac.service;

import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.file.mapper.FileChannelConfigMapper;
import com.example.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import com.example.batch.console.domain.job.mapper.JobDefinitionMapper;
import com.example.batch.console.domain.ops.mapper.ResourceQueueMapper;
import com.example.batch.console.domain.rbac.mapper.TenantMapper;
import com.example.batch.console.domain.rbac.web.response.TenantReadinessResponse;
import com.example.batch.console.domain.rbac.web.response.TenantReadinessResponse.ReadinessItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 租户就绪自检(ADR-026 dry-run 边界内:只看「配置完整性 / 会不会跑」,不看「业务结果对不对」)。
 *
 * <p>复用现有 template / channel / queue / job 的轻量只读 readiness 查询,不新加重 SQL 业务逻辑。检查项:
 *
 * <ul>
 *   <li><b>template</b>:enabled 模板的关键字段空占位 = blocking;IMPORT/EXPORT 缺 default_query_sql 等 =
 *       blocking。
 *   <li><b>channel</b>:enabled 渠道 config_json 空 / 占位 {@code {}} = blocking;非 NONE 鉴权缺端点 = blocking。
 *   <li><b>queue</b>:enabled job 引用的 queue_code 不存在 = blocking(悬空引用)。
 *   <li>disabled 配置仅可疑时进 warning,不阻断(尚未投产)。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsoleTenantReadinessService {

  /** config_json 视为「未配置」的占位形态。 */
  private static final Set<String> EMPTY_JSON_PLACEHOLDERS = Set.of("", "{}", "null");

  private final TenantMapper tenantMapper;
  private final FileTemplateConfigMapper templateMapper;
  private final FileChannelConfigMapper channelMapper;
  private final ResourceQueueMapper resourceQueueMapper;
  private final JobDefinitionMapper jobDefinitionMapper;

  public TenantReadinessResponse check(String tenantId) {
    Guard.requireText(tenantId, "tenantId is required");
    Guard.requireFound(tenantMapper.selectByTenantId(tenantId), "tenant not found: " + tenantId);

    List<ReadinessItem> blocking = new ArrayList<>();
    List<ReadinessItem> warnings = new ArrayList<>();

    checkTemplates(tenantId, blocking, warnings);
    checkChannels(tenantId, blocking, warnings);
    checkJobQueues(tenantId, blocking);

    return new TenantReadinessResponse(
        tenantId, blocking.isEmpty(), List.copyOf(blocking), List.copyOf(warnings));
  }

  private void checkTemplates(
      String tenantId, List<ReadinessItem> blocking, List<ReadinessItem> warnings) {
    for (Map<String, Object> row : templateMapper.selectReadinessRows(tenantId)) {
      String code = str(row, "template_code");
      String type = str(row, "template_type");
      boolean enabled = bool(row, "enabled");
      boolean missingQuery = isBlank(str(row, "default_query_sql"));
      boolean missingMappings = isBlankJson(str(row, "field_mappings"));
      // IMPORT/EXPORT 模板必须有取数 SQL + 字段映射才能真正跑;SHARED 仅片段,不强制。
      boolean queryRelevant = "IMPORT".equals(type) || "EXPORT".equals(type);
      if (queryRelevant && (missingQuery || missingMappings)) {
        String reason =
            "template missing "
                + (missingQuery ? "default_query_sql " : "")
                + (missingMappings ? "field_mappings" : "");
        if (enabled) {
          blocking.add(new ReadinessItem("template", reason.trim(), code));
        } else {
          warnings.add(
              new ReadinessItem(
                  "template", "disabled template incomplete: " + reason.trim(), code));
        }
      }
    }
  }

  private void checkChannels(
      String tenantId, List<ReadinessItem> blocking, List<ReadinessItem> warnings) {
    for (Map<String, Object> row : channelMapper.selectReadinessRows(tenantId)) {
      String code = str(row, "channel_code");
      String authType = str(row, "auth_type");
      boolean enabled = bool(row, "enabled");
      boolean noConfig = isBlankJson(str(row, "config_json"));
      boolean needsCredential = authType != null && !"NONE".equals(authType);
      if (needsCredential && noConfig) {
        String reason = "channel auth_type=" + authType + " but config_json (credential) is empty";
        if (enabled) {
          blocking.add(new ReadinessItem("channel", reason, code));
        } else {
          warnings.add(new ReadinessItem("channel", "disabled " + reason, code));
        }
      }
    }
  }

  private void checkJobQueues(String tenantId, List<ReadinessItem> blocking) {
    List<String> queueCodes = resourceQueueMapper.selectQueueCodes(tenantId);
    Set<String> existing = Set.copyOf(queueCodes);
    for (Map<String, Object> row : jobDefinitionMapper.selectEnabledJobQueueRefs(tenantId)) {
      String queueCode = str(row, "queue_code");
      String jobCode = str(row, "job_code");
      if (!isBlank(queueCode) && !existing.contains(queueCode)) {
        blocking.add(
            new ReadinessItem(
                "queue", "job references missing queue_code '" + queueCode + "'", jobCode));
      }
    }
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }

  private static boolean isBlankJson(String v) {
    return v == null || EMPTY_JSON_PLACEHOLDERS.contains(v.trim());
  }

  private static boolean bool(Map<String, Object> row, String key) {
    return Boolean.TRUE.equals(row.get(key));
  }

  private static String str(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? null : String.valueOf(v);
  }
}
