package com.example.batch.console.service;

import com.example.batch.console.domain.request.ArchiveFileRequest;
import com.example.batch.console.domain.request.DeleteFileRequest;
import com.example.batch.console.domain.request.FileArrivalGroupActionRequest;
import com.example.batch.console.domain.request.PresignDownloadFileRequest;
import com.example.batch.console.domain.request.RedispatchFileRequest;

public interface ConsoleFileApplicationService {

    String archive(ArchiveFileRequest request, String idempotencyKey);

    String delete(DeleteFileRequest request, String idempotencyKey);

    String redispatch(RedispatchFileRequest request, String idempotencyKey);

    String presignDownload(PresignDownloadFileRequest request, String idempotencyKey);

    String operateArrivalGroup(FileArrivalGroupActionRequest request, String idempotencyKey);
}
