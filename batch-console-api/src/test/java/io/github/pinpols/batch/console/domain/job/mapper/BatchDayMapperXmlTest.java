package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class BatchDayMapperXmlTest {

  @Test
  void formalStatisticsStayDryRunIsolatedAndPausedAware() {
    String resource = "mapper/BatchDayMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      byte[] mapperBytes = inputStream.readAllBytes();
      String mapperXml = new String(mapperBytes, StandardCharsets.UTF_8);

      assertThat(mapperXml).contains("'RUNNING', 'PAUSED'");
      assertThat(mapperXml).contains("and ji.dry_run = false");
      assertThat(count(mapperXml, "'RUNNING', 'PAUSED'")).isEqualTo(3);
      assertThat(count(mapperXml, "and ji.dry_run = false")).isEqualTo(3);

      new XMLMapperBuilder(
              new ByteArrayInputStream(mapperBytes),
              configuration,
              resource,
              configuration.getSqlFragments())
          .parse();
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }

  private static int count(String source, String target) {
    return source.split(Pattern.quote(target), -1).length - 1;
  }
}
