package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigPackageExcelValidatorTest {

  @Test
  void processPipelineTypeAllowsProcessStages() {
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE).containsKey("PROCESS");
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE.get("PROCESS"))
        .containsExactlyInAnyOrder("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(ConfigPackageExcelValidator.STAGE_CODES).contains("COMPUTE", "COMMIT");
  }
}
