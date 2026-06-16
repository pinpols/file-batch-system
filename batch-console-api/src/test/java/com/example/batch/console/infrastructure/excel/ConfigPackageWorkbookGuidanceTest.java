package com.example.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.PipelineType;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/**
 * 配置引导防漂移单测:锁定 {@link ConfigPackageExcelWorkbookWriter} 生成的模板里 ① 新增的「四类Worker示例」「依赖说明」只读 sheet 存在且
 * 表头/行数与设计一致; ② 「字段说明」sheet 新增「填写示例」列且最难字段给出真实非空片段(import vs export 两套结构都覆盖); ③ 四类Worker示例的
 * worker_type / pipeline_type 与后端 enum 集合一致(enum 新增成员而示例未同步即挂)。
 */
class ConfigPackageWorkbookGuidanceTest {

  private XSSFWorkbook buildTemplate() throws Exception {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer = new ConfigPackageExcelWorkbookWriter(ms);
    byte[] bytes = writer.buildTemplateWorkbook();
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  @Test
  void fourWorkerExampleSheetExistsWithFourRowsAndHeaders() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet(ConfigPackageExcelWorkbookWriter.SHEET_NAME_FOUR_WORKER);
      assertThat(sheet).as("四类Worker示例 sheet 必须存在").isNotNull();
      // row0 = 提示行, row1 = 表头, row2..row5 = 4 类 worker
      Row header = sheet.getRow(1);
      List<String> headerValues = new ArrayList<>();
      for (int c = 0; c < ConfigPackageExcelWorkbookWriter.FOUR_WORKER_HEADERS.length; c++) {
        headerValues.add(header.getCell(c).getStringCellValue());
      }
      assertThat(headerValues)
          .containsExactly(ConfigPackageExcelWorkbookWriter.FOUR_WORKER_HEADERS);
      assertThat(ConfigPackageExcelWorkbookWriter.FOUR_WORKER_ROWS).hasSize(4);
      List<String> workerTypes = new ArrayList<>();
      List<String> pipelineTypes = new ArrayList<>();
      for (int r = 0; r < 4; r++) {
        Row row = sheet.getRow(2 + r);
        workerTypes.add(row.getCell(0).getStringCellValue());
        pipelineTypes.add(row.getCell(2).getStringCellValue());
      }
      assertThat(workerTypes).containsExactly("IMPORT", "EXPORT", "PROCESS", "DISPATCH");
      // pipeline_type / worker_type 必须都是合法 enum code(enum 新增/改名而示例未同步即挂)。
      Set<String> pipelineCodes = DictEnum.codes(PipelineType.class);
      Set<String> jobCodes = DictEnum.codes(JobType.class);
      assertThat(pipelineTypes).allMatch(pipelineCodes::contains);
      assertThat(workerTypes).allMatch(jobCodes::contains);
    }
  }

  @Test
  void dependencySheetExistsWithHeadersAndCoreRows() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet(ConfigPackageExcelWorkbookWriter.SHEET_NAME_DEPENDENCY);
      assertThat(sheet).as("依赖说明 sheet 必须存在").isNotNull();
      Row header = sheet.getRow(1);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("source_sheet");
      assertThat(header.getCell(4).getStringCellValue()).isEqualTo("db_fallback");
      // 关键引用关系行必须在(job->queue / step->channel / default_params.templateCode->file_template)。
      List<String> firstCol = new ArrayList<>();
      List<String> targets = new ArrayList<>();
      for (String[] r : ConfigPackageExcelWorkbookWriter.DEPENDENCY_ROWS) {
        firstCol.add(r[0]);
        targets.add(r[2]);
      }
      assertThat(firstCol).contains("job_definition", "pipeline_step_definition", "workflow_node");
      assertThat(targets).contains("resource_queue", "file_channel_config", "file_template_config");
    }
  }

  @Test
  void fieldGuideHasFillExampleColumnWithRealNonEmptyExamples() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet("字段说明");
      assertThat(sheet).isNotNull();
      Row header = sheet.getRow(0);
      // 第 9 列(index 8)= 填写示例
      assertThat(header.getCell(8).getStringCellValue()).isEqualTo("填写示例");

      // 收集 file_template_config 段的 field_mappings / query_param_schema 行的填写示例,
      // 必须同时含 import 和 export 两套结构关键字,且非空 {}。
      String fieldMappingsFill = fillExampleOf(sheet, "field_mappings");
      assertThat(fieldMappingsFill)
          .contains("targetColumn") // import 结构
          .contains("sourceColumn") // export 结构
          .doesNotContain("\"source\":\"name\",\"target\":\"NAME\"");
      String queryParamFill = fillExampleOf(sheet, "query_param_schema");
      assertThat(queryParamFill)
          .contains("jdbcMappedImport") // import
          .contains("conflictColumns")
          .isNotEqualTo("{}");

      // channel config_json 填写示例必须含 endpoint + credentials(真实结构),非空 {}。
      String configJsonFill = fillExampleOf(sheet, "config_json");
      assertThat(configJsonFill).contains("endpoint").contains("credentials").isNotEqualTo("{}");
    }
  }

  private static String fillExampleOf(Sheet sheet, String colName) {
    for (Row row : sheet) {
      if (row.getRowNum() == 0) {
        continue;
      }
      if (colName.equals(row.getCell(1).getStringCellValue())) {
        return row.getCell(8).getStringCellValue();
      }
    }
    throw new AssertionError("column not found in field guide: " + colName);
  }
}
