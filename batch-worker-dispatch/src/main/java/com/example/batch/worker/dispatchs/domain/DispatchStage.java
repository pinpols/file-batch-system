package com.example.batch.worker.dispatchs.domain;

public enum DispatchStage {
    PREPARE,
    DISPATCH,
    ACK,
    RETRY,
    COMPENSATE,
    COMPLETE
}
