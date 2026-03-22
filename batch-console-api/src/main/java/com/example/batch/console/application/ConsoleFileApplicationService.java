package com.example.batch.console.application;

import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;

public interface ConsoleFileApplicationService {

    String archive(ArchiveFileRequest request, String idempotencyKey);

    String delete(DeleteFileRequest request, String idempotencyKey);

    String redispatch(RedispatchFileRequest request, String idempotencyKey);

    String operateArrivalGroup(FileArrivalGroupActionRequest request, String idempotencyKey);
}
