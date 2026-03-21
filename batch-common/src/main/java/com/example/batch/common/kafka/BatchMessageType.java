package com.example.batch.common.kafka;

public enum BatchMessageType {
    TASK_DISPATCH,
    TASK_RESULT,
    TASK_RETRY,
    TASK_DEAD_LETTER,
    OUTBOX_EVENT,
    WORKER_HEARTBEAT
}
