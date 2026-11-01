package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.application.service.dq.DataQualityRuleApplicationService;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("数据质量规则持久化和内部接口")
class DataQualityRulePersistenceIntegrationTest {

  @Container private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static SqlSessionFactory sessions;

  @BeforeAll
  static void configureMapper() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    Configuration config =
        new Configuration(new Environment("dq-rule", new JdbcTransactionFactory(), source));
    try (var input = Resources.getResourceAsStream("mapper/DataQualityRuleMapper.xml")) {
      new XMLMapperBuilder(input, config, "mapper/DataQualityRuleMapper.xml", config.getSqlFragments())
          .parse();
    }
    sessions = new SqlSessionFactoryBuilder().build(config);
  }

  @Test
  @DisplayName("响应反映实际行，跨租户和不存在的更新不伪报成功")
  void shouldReturnPersistedValuesAndRejectMissingOrCrossTenantUpdates() throws Exception {
    try (SqlSession session = sessions.openSession()) {
      DataQualityRuleMapper mapper = session.getMapper(DataQualityRuleMapper.class);
      MockMvc mvc = MockMvcBuilders.standaloneSetup(
              new DataQualityRuleController(new DataQualityRuleApplicationService(mapper)))
          .setControllerAdvice(OrchestratorApiExceptionHandler.forStandaloneTest())
          .build();
      String original = """
          {"ruleCode":"ORIGINAL","ruleName":"original name","ruleType":"ROW_LEVEL",
           "scopeBusinessKey":"job:","expression":"1=1"}
          """;
      String createJson = mvc.perform(post("/internal/orchestrator/dq/rules")
              .param("tenantId", "tenant-a")
              .contentType(MediaType.APPLICATION_JSON)
              .content(original))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.severity").value("WARN"))
          .andExpect(jsonPath("$.data.enabled").value(true))
          .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
          .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
          .andReturn().getResponse().getContentAsString();
      long id = new ObjectMapper().readTree(createJson).path("data").path("id").asLong();
      String update = """
          {"tenantId":"tenant-a","ruleCode":"CHANGED","ruleName":"updated name",
           "ruleType":"ROW_LEVEL","scopeBusinessKey":"job:","expression":"1=1",
           "severity":"WARN","enabled":true}
          """;
      mvc.perform(put("/internal/orchestrator/dq/rules/{id}", id)
              .param("tenantId", "tenant-b")
              .contentType(MediaType.APPLICATION_JSON).content(update))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
      mvc.perform(put("/internal/orchestrator/dq/rules/{id}", id + 1000)
              .param("tenantId", "tenant-a")
              .contentType(MediaType.APPLICATION_JSON).content(update))
          .andExpect(status().isNotFound());
      DataQualityRuleEntity unchanged = mapper.selectById(id, "tenant-a");
      assertThat(unchanged.getRuleName()).isEqualTo("original name");
      mvc.perform(put("/internal/orchestrator/dq/rules/{id}", id)
              .param("tenantId", "tenant-a")
              .contentType(MediaType.APPLICATION_JSON).content(update))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.ruleCode").value("ORIGINAL"))
          .andExpect(jsonPath("$.data.ruleName").value("updated name"))
          .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
      assertThat(mapper.selectById(id, "tenant-a").getRuleName()).isEqualTo("updated name");
      session.rollback();
    }
  }
}
