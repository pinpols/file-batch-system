package io.github.pinpols.batch.worker.imports.domain;

public record CustomerImportPayload(
    String customerNo,
    String customerName,
    String customerType,
    String certificateNo,
    String mobileNo,
    String email,
    String status) {}
