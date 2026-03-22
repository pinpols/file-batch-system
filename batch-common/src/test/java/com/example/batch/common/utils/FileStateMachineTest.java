package com.example.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.FileStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import org.junit.jupiter.api.Test;

class FileStateMachineTest {

    @Test
    void shouldAcceptInitialStatuses() {
        FileStateMachine.assertInitialStatus(FileStatus.RECEIVED.name());
        FileStateMachine.assertInitialStatus(FileStatus.GENERATED.name());
    }

    @Test
    void shouldRejectNonInitialStatusesForInitialAssertion() {
        assertThatThrownBy(() -> FileStateMachine.assertInitialStatus(FileStatus.PARSING.name()))
                .isInstanceOf(BizException.class)
                .extracting(BizException.class::cast)
                .extracting(BizException::getCode)
                .isEqualTo(ResultCode.STATE_CONFLICT);
    }

    @Test
    void shouldAllowSameStatusTransition() {
        FileStateMachine.assertTransition(FileStatus.RECEIVED.name(), FileStatus.RECEIVED.name());
        assertThat(FileStateMachine.canTransition(FileStatus.RECEIVED.name(), FileStatus.RECEIVED.name())).isTrue();
    }

    @Test
    void shouldAllowValidImportChain() {
        FileStateMachine.assertTransition(FileStatus.RECEIVED.name(), FileStatus.PARSING.name());
        FileStateMachine.assertTransition(FileStatus.PARSING.name(), FileStatus.PARSED.name());
        FileStateMachine.assertTransition(FileStatus.PARSED.name(), FileStatus.VALIDATED.name());
        FileStateMachine.assertTransition(FileStatus.VALIDATED.name(), FileStatus.LOADED.name());
        FileStateMachine.assertTransition(FileStatus.LOADED.name(), FileStatus.ARCHIVED.name());
    }

    @Test
    void shouldRejectIllegalTransition() {
        assertThatThrownBy(() -> FileStateMachine.assertTransition(FileStatus.RECEIVED.name(), FileStatus.LOADED.name()))
                .isInstanceOf(BizException.class)
                .extracting(BizException.class::cast)
                .extracting(BizException::getCode)
                .isEqualTo(ResultCode.STATE_CONFLICT);
    }

    @Test
    void shouldReportIllegalTransitionViaCanTransition() {
        assertThat(FileStateMachine.canTransition(FileStatus.RECEIVED.name(), FileStatus.LOADED.name())).isFalse();
    }

    @Test
    void shouldRejectBlankStatusCode() {
        assertThatThrownBy(() -> FileStateMachine.assertTransition("", FileStatus.RECEIVED.name()))
                .isInstanceOf(BizException.class)
                .extracting(BizException.class::cast)
                .extracting(BizException::getCode)
                .isEqualTo(ResultCode.INVALID_ARGUMENT);
    }
}
