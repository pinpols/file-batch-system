package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.StepRegistryQueryMapper;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigPackageExcelValidatorTest {

  @Test
  void processPipelineTypeAllowsProcessStages() {
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE).containsKey("PROCESS");
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE.get("PROCESS"))
        .containsExactlyInAnyOrder("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(ConfigPackageExcelValidator.STAGE_CODES).contains("COMPUTE", "COMMIT");
  }

  @Test
  void validatesFileTemplateConfigSheetRows() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session =
        session(
            List.of(
                fileTemplateRow("TPL_IMPORT_CUSTOMER", "1"),
                fileTemplateRow("TPL_IMPORT_CUSTOMER", "1")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.fileTemplates().sheetName())
        .isEqualTo(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET);
    assertThat(result.fileTemplates().valid()).isEqualTo(1);
    assertThat(result.fileTemplates().invalid()).isEqualTo(1);
    assertThat(result.allIssues())
        .anySatisfy(
            issue ->
                assertThat(issue.message()).contains("duplicate template_code + version in excel"));
  }

  @Test
  void reportsUnknownTemplateCodeReferencesFromJobDefaultParams() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session =
        session(
            List.of(),
            List.of(
                Map.of(
                    "tenant_id",
                    "t1",
                    "job_code",
                    "JOB_IMPORT_CUSTOMER",
                    "job_name",
                    "导入客户",
                    "job_type",
                    "IMPORT",
                    "schedule_type",
                    "MANUAL",
                    "default_params",
                    "{\"templateCode\":\"TPL_MISSING\"}")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.allIssues())
        .anySatisfy(
            issue -> {
              assertThat(issue.sheetName()).isEqualTo(ConfigPackageExcelValidator.JOB_SHEET);
              assertThat(issue.columnName())
                  .isEqualTo(ConfigPackageExcelValidator.COL_DEFAULT_PARAMS);
              assertThat(issue.message()).contains("TPL_MISSING");
            });
  }

  private static ConfigPackageExcelValidator validator() {
    return new ConfigPackageExcelValidator(
        mock(JobDefinitionMapper.class),
        mock(PipelineDefinitionMapper.class),
        mock(StepRegistryQueryMapper.class),
        mock(FileTemplateConfigMapper.class));
  }

  private static PackageExcelSession session(List<Map<String, String>> fileTemplateRows) {
    return session(fileTemplateRows, List.of());
  }

  private static PackageExcelSession session(
      List<Map<String, String>> fileTemplateRows, List<Map<String, String>> jobRows) {
    return new PackageExcelSession(
        "package.xlsx",
        "t1",
        Instant.EPOCH,
        jobRows,
        List.of(),
        fileTemplateRows,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static Map<String, String> fileTemplateRow(String templateCode, String version) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("template_code", templateCode);
    row.put("template_name", "客户导入模板");
    row.put("template_type", "IMPORT");
    row.put("file_format_type", "DELIMITED");
    row.put("checksum_type", "NONE");
    row.put("compress_type", "NONE");
    row.put("encrypt_type", "NONE");
    row.put("version", version);
    return row;
  }
}
