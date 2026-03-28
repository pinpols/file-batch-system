package com.example.batch.console.application;

import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;
import com.example.batch.console.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.web.response.ConsolePresignDownloadResponse;

public interface ConsoleFileApplicationService {

    ConsoleFileOperationResponse archive(ArchiveFileRequest request, String idempotencyKey);

    ConsoleFileOperationResponse delete(DeleteFileRequest request, String idempotencyKey);

    ConsoleFileOperationResponse redispatch(RedispatchFileRequest request, String idempotencyKey);

    ConsolePresignDownloadResponse presignDownload(PresignDownloadFileRequest request, String idempotencyKey);

    ConsoleFileOperationResponse operateArrivalGroup(FileArrivalGroupActionRequest request, String idempotencyKey);
}
