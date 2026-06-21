package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ADR-046:到达组凑齐(TRIGGERED)→ 若该组是文件束(成员 file_record 的 metadata 带 {@code bundleJobCode})→ 据 {@code
 * bundleJobCode} 指向作业的类型发一个 {@code BUNDLE_*} launch。
 *
 * <p>束 launch 把组内 N 个文件作为 {@code params.bundleFiles=[{sourceFileId, templateCode?, targetRef?}]} 传给
 * orchestrator,由 {@code DefaultSchedulePlanBuilder} 展成 N 个异构 partition;承重字段按束类型(绑定 profile)校验:导入须
 * sourceFileId+templateCode(目标表从模板推),分发须 sourceFileId+targetRef(下游渠道 channel_code)。本类按 file_record
 * metadata 里出现的键 emit 一个超集(templateCode 取 {@code bundleTemplateCode}、targetRef 取 {@code
 * bundleTargetRef}),具体保留/丢弃交 orchestrator 的 类型化 extract,故本类对绑定 profile 不感知。
 *
 * <p>导出束(manifest-only):导出无输入数据文件,由 scanner 把导出清单本身登记为一条自完整 trigger 记录,其 {@code
 * metadata.bundleExportTemplates} 携带导出模板列表;本类据此一个声明展成 N 项 {@code {templateCode}} (无 sourceFileId)。
 *
 * <p><b>边界与安全</b>:仅对带 {@code bundleJobCode} 的组发(普通到达组 per-file 默认不变);异常隔离(launch 失败只 ERROR 日志,不阻断
 * governance sweep);幂等靠确定性 {@code requestId}(同组同 bizDate → 同 requestId → {@code trigger_request}
 * UNIQUE 兜重复发射 + 下轮 sweep 跳过 TRIGGERED 组)。
 *
 * <p>抽成独立 {@link Component} 而非内联进 {@code FileGovernanceScheduler}:可单测(mock LaunchService) + 经
 * {@link ObjectProvider} 懒取避免与 LaunchService 的依赖环。
 */
@Slf4j
@Component
public class BundleArrivalLauncher {

  private static final String META_BUNDLE_JOB_CODE = "bundleJobCode";
  private static final String META_BUNDLE_TEMPLATE_CODE = "bundleTemplateCode";
  // ADR-046 Phase3:分发束 per-file 绑定下游渠道(channel_code),落 file_record metadata.bundleTargetRef。
  private static final String META_BUNDLE_TARGET_REF = "bundleTargetRef";
  // ADR-046 Phase3:导出束「manifest-only」——一条 trigger 记录的 metadata 携带导出模板 code 列表(导出无源文件,
  // 一个声明展成 N 个 partition),scanner 落 metadata.bundleExportTemplates。
  private static final String META_BUNDLE_EXPORT_TEMPLATES = "bundleExportTemplates";

  private final ObjectProvider<LaunchService> launchServiceProvider;

  public BundleArrivalLauncher(ObjectProvider<LaunchService> launchServiceProvider) {
    this.launchServiceProvider = launchServiceProvider;
  }

  /** 到达组凑齐时调用;非束组(无 bundleJobCode)直接返回,不发 launch。 */
  public void launchIfBundle(
      String tenantId, String fileGroupCode, List<Map<String, Object>> groupFiles) {
    try {
      if (groupFiles == null || groupFiles.isEmpty() || !Texts.hasText(tenantId)) {
        return;
      }
      String bundleJobCode = null;
      LocalDate bizDate = null;
      List<Map<String, Object>> bundleFiles = new ArrayList<>();
      for (Map<String, Object> file : groupFiles) {
        Map<String, Object> meta = parseMetadata(file.get("metadata_json"));
        String jobCode = text(meta.get(META_BUNDLE_JOB_CODE));
        String templateCode = text(meta.get(META_BUNDLE_TEMPLATE_CODE));
        String targetRef = text(meta.get(META_BUNDLE_TARGET_REF));
        Long fileId = toLong(file.get("id"));
        if (bundleJobCode == null && jobCode != null) {
          bundleJobCode = jobCode;
        }
        if (bizDate == null) {
          bizDate = toLocalDate(file.get("biz_date"));
        }
        // 导出束(manifest-only):一条 trigger 记录的 bundleExportTemplates 列表 → 每个模板展一项
        // {templateCode}(导出无源文件,不带 sourceFileId)。
        List<String> exportTemplates = textList(meta.get(META_BUNDLE_EXPORT_TEMPLATES));
        if (!exportTemplates.isEmpty()) {
          for (String tpl : exportTemplates) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("templateCode", tpl);
            bundleFiles.add(entry);
          }
          continue;
        }
        // 导入/分发束:每个到达文件 emit 一个超集绑定(有文件 + 至少一个绑定键),
        // 具体保留哪些字段交 orchestrator 的类型化 BundlePlanParams.extract 按束类型裁定。
        if (fileId != null && (templateCode != null || targetRef != null)) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("sourceFileId", fileId);
          if (templateCode != null) {
            entry.put("templateCode", templateCode);
          }
          if (targetRef != null) {
            entry.put("targetRef", targetRef);
          }
          bundleFiles.add(entry);
        }
      }

      if (bundleJobCode == null) {
        return; // 普通到达组,不是文件束
      }
      if (bundleFiles.isEmpty()) {
        log.warn(
            "bundle arrival group has bundleJobCode but no usable file(file + 模板/渠道 绑定缺):"
                + " tenantId={}, fileGroupCode={}, jobCode={}",
            tenantId,
            fileGroupCode,
            bundleJobCode);
        return;
      }

      // 确定性 requestId:同组同 bizDate 只 launch 一次(trigger_request UNIQUE 兜底)
      String requestId = "bundle-arrival-" + tenantId + "-" + fileGroupCode + "-" + bizDate;
      LaunchRequest request =
          LaunchRequest.builder()
              .tenantId(tenantId)
              .jobCode(bundleJobCode)
              .bizDate(bizDate)
              .triggerType(TriggerType.EVENT)
              .requestId(requestId)
              .traceId(IdGenerator.newTraceId())
              .params(Map.of("bundleFiles", bundleFiles))
              .build();
      launchServiceProvider.getObject().launch(request);
      log.info(
          "bundle arrival launched: tenantId={}, fileGroupCode={}, jobCode={}, fileCount={},"
              + " bizDate={}, requestId={}",
          tenantId,
          fileGroupCode,
          bundleJobCode,
          bundleFiles.size(),
          bizDate,
          requestId);
    } catch (Exception ex) {
      // 异常隔离:束 launch 失败不阻断到达组治理 sweep
      log.error(
          "bundle arrival launch failed (governance sweep 继续): tenantId={}, fileGroupCode={}",
          tenantId,
          fileGroupCode,
          ex);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseMetadata(Object metadataJson) {
    if (!(metadataJson instanceof String json) || json.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed = JsonUtils.fromJson(json, Map.class);
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      return Map.of();
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value).trim();
    return s.isEmpty() ? null : s;
  }

  /** 把 metadata 里的「字符串列表」字段(如 bundleExportTemplates)归一成非空 trim 后的 List;非 List / 空 → 空表。 */
  private static List<String> textList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      String s = text(item);
      if (s != null) {
        result.add(s);
      }
    }
    return result;
  }

  private static Long toLong(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static LocalDate toLocalDate(Object value) {
    if (value instanceof LocalDate d) {
      return d;
    }
    if (value instanceof java.sql.Date sqlDate) {
      return sqlDate.toLocalDate();
    }
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(String.valueOf(value).trim());
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
