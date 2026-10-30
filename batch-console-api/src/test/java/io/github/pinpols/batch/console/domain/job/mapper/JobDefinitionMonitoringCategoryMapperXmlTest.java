package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作业监控分类查询映射")
class JobDefinitionMonitoringCategoryMapperXmlTest {

  @Test
  @DisplayName("列表和总数查询使用相同监控分类条件")
  void shouldApplyCategoryFilters_whenBuildingRowsAndCountQueries() throws Exception {
    Configuration configuration = new Configuration();
    parseMapper(configuration, "mapper/CommonFragments.xml");
    parseMapper(configuration, "mapper/JobDefinitionMapper.xml");

    for (String category : new String[] {"DEPENDENCY", "SCHEDULED", "RUNTIME_ONLY"}) {
      JobDefinitionQuery query = JobDefinitionQuery.builder()
          .tenantId("tenant-a")
          .monitoringCategory(category)
          .pageRequest(new PageRequest(1, 20))
          .build();

      String selectSql = configuration
          .getMappedStatement(JobDefinitionMapper.class.getName() + ".selectByQuery")
          .getBoundSql(query)
          .getSql();
      String countSql = configuration
          .getMappedStatement(JobDefinitionMapper.class.getName() + ".countByQuery")
          .getBoundSql(query)
          .getSql();

      String predicate = categoryPredicate(category);
      assertThat(selectSql).contains(predicate);
      assertThat(countSql).contains(predicate.replace("jd.", ""));
    }
  }

  @Test
  @DisplayName("上游作业过滤通过依赖关系同时应用于列表和总数查询")
  void shouldFilterByUpstreamDependency_whenBuildingRowsAndCountQueries() throws Exception {
    Configuration configuration = new Configuration();
    parseMapper(configuration, "mapper/CommonFragments.xml");
    parseMapper(configuration, "mapper/JobDefinitionMapper.xml");
    JobDefinitionQuery query = JobDefinitionQuery.builder()
        .tenantId("tenant-a")
        .exactJobCode("CURRENT_JOB")
        .dependsOnJobCode("UPSTREAM_JOB")
        .pageRequest(new PageRequest(1, 20))
        .build();

    String selectSql = configuration
        .getMappedStatement(JobDefinitionMapper.class.getName() + ".selectByQuery")
        .getBoundSql(query)
        .getSql();
    String countSql = configuration
        .getMappedStatement(JobDefinitionMapper.class.getName() + ".countByQuery")
        .getBoundSql(query)
        .getSql();

    assertThat(selectSql).contains("exists (select 1 from batch.job_dependency jdep");
    assertThat(selectSql).contains("jdep.upstream_job_code = ?");
    assertThat(selectSql).contains("jd.job_code = ?");
    assertThat(countSql).contains("exists (select 1 from batch.job_dependency jdep");
    assertThat(countSql).contains("jdep.upstream_job_code = ?");
    assertThat(countSql).contains("job_code = ?");
  }

  @Test
  @DisplayName("作业定义查询默认只包含启用作业，includeDisabled 可显式移除启用条件")
  void shouldPreserveEnabledDefaultUnlessDisabledJobsAreRequested() throws Exception {
    Configuration configuration = new Configuration();
    parseMapper(configuration, "mapper/CommonFragments.xml");
    parseMapper(configuration, "mapper/JobDefinitionMapper.xml");
    JobDefinitionQuery enabledOnly = JobDefinitionQuery.builder()
        .tenantId("tenant-a")
        .enabled(true)
        .pageRequest(new PageRequest(1, 20))
        .build();
    JobDefinitionQuery allStatuses = JobDefinitionQuery.builder()
        .tenantId("tenant-a")
        .pageRequest(new PageRequest(1, 20))
        .build();

    String enabledSql = configuration
        .getMappedStatement(JobDefinitionMapper.class.getName() + ".selectByQuery")
        .getBoundSql(enabledOnly)
        .getSql();
    String allStatusesSql = configuration
        .getMappedStatement(JobDefinitionMapper.class.getName() + ".selectByQuery")
        .getBoundSql(allStatuses)
        .getSql();

    assertThat(enabledSql).contains("jd.enabled = ?");
    assertThat(allStatusesSql).doesNotContain("jd.enabled = ?");
  }

  private static String categoryPredicate(String category) {
    return switch (category) {
      case "DEPENDENCY" -> "exists (select 1 from batch.job_dependency jdep";
      case "SCHEDULED" -> "jd.schedule_type = 'CRON'";
      case "RUNTIME_ONLY" -> "jd.schedule_type != 'CRON'";
      default -> throw new IllegalArgumentException("Unsupported category: " + category);
    };
  }

  private static void parseMapper(Configuration configuration, String resource) throws Exception {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      byte[] mapperBytes = inputStream.readAllBytes();
      new XMLMapperBuilder(
              new ByteArrayInputStream(mapperBytes),
              configuration,
              resource,
              configuration.getSqlFragments())
          .parse();
    }
  }
}
