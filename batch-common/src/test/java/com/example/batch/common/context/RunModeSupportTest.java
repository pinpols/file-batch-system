package com.example.batch.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.RunMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunModeSupportTest {

    @Test
    void shouldCanonicalizeLegacyAliasWhenCopying() {
        Map<String, Object> normalized = RunModeSupport.copyWithDefault(
                Map.of(RunModeSupport.LEGACY_RUN_MODE, "retry"),
                RunMode.NORMAL
        );

        assertThat(normalized)
                .containsEntry(RunModeSupport.RUN_MODE, RunMode.RETRY.code())
                .doesNotContainKey(RunModeSupport.LEGACY_RUN_MODE);
    }

    @Test
    void shouldFallBackToDefaultWhenRunModeIsMissing() {
        Map<String, Object> normalized = RunModeSupport.copyWithDefault(
                Map.of("jobCode", "IMPORT_JOB"),
                RunMode.RERUN
        );

        assertThat(normalized).containsEntry(RunModeSupport.RUN_MODE, RunMode.RERUN.code());
    }
}
