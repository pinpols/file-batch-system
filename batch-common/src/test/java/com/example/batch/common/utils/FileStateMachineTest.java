package com.example.batch.common.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.batch.common.exception.BizException;
import org.junit.jupiter.api.Test;

class FileStateMachineTest {

    @Test
    void shouldAllowNormalImportProgression() {
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("RECEIVED", "PARSING"));
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("PARSING", "PARSED"));
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("PARSED", "VALIDATED"));
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("VALIDATED", "LOADED"));
    }

    @Test
    void shouldAllowDispatchLifecycle() {
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("GENERATED", "DISPATCHING"));
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("DISPATCHING", "DISPATCHED"));
        assertDoesNotThrow(() -> FileStateMachine.assertTransition("DISPATCHED", "ARCHIVED"));
    }

    @Test
    void shouldRejectIllegalTransition() {
        assertThrows(BizException.class, () -> FileStateMachine.assertTransition("RECEIVED", "DISPATCHED"));
        assertThrows(BizException.class, () -> FileStateMachine.assertTransition("ARCHIVED", "GENERATED"));
    }
}
