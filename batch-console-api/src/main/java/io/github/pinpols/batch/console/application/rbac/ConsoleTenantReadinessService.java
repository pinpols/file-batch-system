package io.github.pinpols.batch.console.application.rbac;

import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigReadinessView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigReadinessView;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionQueueRefView;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.TenantReadinessResponse;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.TenantReadinessResponse.ReadinessItem;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
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

  /** 配置模板内字段说明 / 五类Worker示例 sheet 的口径引用。 */
  private static final String DOC_FIELD_GUIDE = DOC_QUICKSTART + " #字段说明 / 配置模板『字段说明』sheet";

  private final TenantMapper tenantMapper;
  private final FileTemplateConfigMapper templateMapper;
  private final FileChannelConfigMapper channelMapper;
  private final ResourceQueueMapper resourceQueueMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final ConsoleTenantGuard tenantGuard;

  public TenantReadinessResponse check(String tenantId) {
    Guard.requireText(tenantId, "tenantId is required");
    // SEC(跨租户越权修复):非全局角色只能对自身租户跑就绪自检,tenantId 与 JWT 不符 → FORBIDDEN;
    // 全局角色(ADMIN / AUDITOR)及内部 provision 编排(ADMIN 上下文)可查任意租户。
    tenantGuard.assertTenantAllowed(tenantId);
    Map<String, Object> tenant = tenantMapper.selectByTenantId(tenantId);
    Guard.requireFound(tenant, "tenant not found: " + tenantId);

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
    for (FileTemplateConfigReadinessView row : templateMapper.selectReadinessRows(tenantId)) {
      String code = row.templateCode();
      String type = row.templateType();
      boolean enabled = Boolean.TRUE.equals(row.enabled());
      boolean missingQuery = isBlank(row.defaultQuerySql());
      Object fieldMappings = row.fieldMappings();
      String fieldMappingsText = fieldMappings == null ? null : String.valueOf(fieldMappings);
      boolean missingMappings = isBlankJson(fieldMappingsText);
      // IMPORT/EXPORT 模板必须有取数 SQL + 字段映射才能真正跑;SHARED 仅片段,不强制。
      boolean queryRelevant = "IMPORT".equals(type) || "EXPORT".equals(type);
      boolean queryConfigurationIsIncomplete = queryRelevant && (missingQuery || missingMappings);
      if (queryConfigurationIsIncomplete) {
        String reason = "template missing "
            + (missingQuery ? "default_query_sql " : "")
            + (missingMappings ? "field_mappings" : "");
        if (enabled) {
          String hint = "在对应场景配置包的文件模板 Sheet 为模板 '"
              + code
              + "' 填写 "
              + (missingQuery ? "default_query_sql(EXPORT 导出 SQL)" : "")
              + (missingQuery && missingMappings ? " 和 " : "")
              + (missingMappings ? "field_mappings(字段映射)" : "")
              + "；使用对应场景模板中的字段说明核对格式和适用条件。";
          blocking.add(new ReadinessItem("template", reason.trim(), code, hint, DOC_FIELD_GUIDE));
        } else {
          warnings.add(new ReadinessItem(
              "template", "disabled template incomplete: " + reason.trim(), code));
        }
      }
    }
  }

  private void checkChannels(
      String tenantId, List<ReadinessItem> blocking, List<ReadinessItem> warnings) {
    for (FileChannelConfigReadinessView row : channelMapper.selectReadinessRows(tenantId)) {
      String code = row.channelCode();
      String authType = row.authType();
      boolean enabled = Boolean.TRUE.equals(row.enabled());
      boolean noConfig = isBlankJson(row.configJson());
      boolean needsCredential = authType != null && !"NONE".equals(authType);
      if (needsCredential && noConfig) {
        String reason = "channel auth_type=" + authType + " but config_json (credential) is empty";
        if (enabled) {
          String hint = "在配置模板 file_channel_config sheet 为通道 '"
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
    for (JobDefinitionQueueRefView row : jobDefinitionMapper.selectEnabledJobQueueRefs(tenantId)) {
      String queueCode = row.queueCode();
      String jobCode = row.jobCode();
      if (!isBlank(queueCode) && !existing.contains(queueCode)) {
        String hint = "作业 '"
            + jobCode
            + "' 引用的物理队列不可用；请由平台运维核对该租户的队列配置和作业路由后处理。"
            + "租户配置包不能创建队列，也不能调整物理队列引用。";
        blocking.add(new ReadinessItem(
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

}
