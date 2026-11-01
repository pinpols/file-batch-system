package io.github.pinpols.batch.orchestrator.domain.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("等待关系分页游标")
class WaitRelationshipQueryTest {

  @ParameterizedTest(name = "游标类型 {0} 和 ID {1} 的有效性为 {2}")
  @CsvSource(
      value = {
        "NULL,NULL,true",
        "JOB,NULL,false",
        "NULL,1,false",
        "JOB,1,true",
        "JOB,9223372036854775807,true",
        "JOB,9223372036854775808,false",
        "JOB,0,false",
        "JOB,-1,false",
        "JOB,01,false",
        "JOB,not-a-number,false"
      },
      nullValues = "NULL")
  @DisplayName("游标类型和正数 ID 必须成对且不超出 64 位范围")
  void shouldValidateCursorPairAndSignedLongRange(
      String cursorType, String cursorId, boolean expectedValid) {
    WaitRelationshipQuery query = WaitRelationshipQuery.all("tenant-a", cursorType, cursorId, 15);
    assertThat(query.isCursorValid()).isEqualTo(expectedValid);
  }
}
