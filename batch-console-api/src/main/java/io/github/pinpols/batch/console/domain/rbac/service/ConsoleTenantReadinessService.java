package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.web.response.TenantReadinessResponse;
import io.github.pinpols.batch.console.domain.rbac.web.response.TenantReadinessResponse.ReadinessItem;
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

  /** quickstart 文档路径(由主进程另建);readiness 只在 docRef 里引用,不负责生成。 */
  private static final String DOC_QUICKSTART = "docs/runbook/first-tenant-config-quickstart.md";

  /** 配置模板内字段说明 / 四类Worker示例 sheet 的口径引用。 */
  private static final String DOC_FIELD_GUIDE = DOC_QUICKSTART + " #字段说明 / 配置模板『字段说明』sheet";

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
          String hint =
              "在配置模板 file_template_config sheet 为模板 '"
                  + code
                  + "' 填写 "
                  + (missingQuery ? "default_query_sql(EXPORT 导出 SQL)" : "")
                  + (missingQuery && missingMappings ? " 和 " : "")
                  + (missingMappings ? "field_mappings(字段映射)" : "")
                  + "；结构参考『四类Worker示例』sheet 与『字段说明』的填写示例列。";
          blocking.add(new ReadinessItem("template", reason.trim(), code, hint, DOC_FIELD_GUIDE));
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
          String hint =
              "在配置模板 file_channel_config sheet 为通道 '"
                  + code
                  + "' 的 config_json 填写凭据(auth_type="
                  + authType
                  + " 需 endpoint + auth + credentials)；结构参考『字段说明』config_json 的填写示例列。";
          blocking.add(new ReadinessItem("channel", reason, code, hint, DOC_FIELD_GUIDE));
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
        String hint =
            "在配置模板 resource_queue sheet 新增 queue_code='"
                + queueCode
                + "'(作业 '"
                + jobCode
                + "' 引用)，或把作业 queue_code 改为已存在的队列；resource_queue 是可选 sheet，引用时必填。";
        blocking.add(
            new ReadinessItem(
                "queue",
                "job references missing queue_code '" + queueCode + "'",
                jobCode,
                hint,
                DOC_QUICKSTART));
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
