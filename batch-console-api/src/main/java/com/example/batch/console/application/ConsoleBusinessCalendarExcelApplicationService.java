package com.example.batch.console.application;

import com.example.batch.console.web.request.BusinessCalendarExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 工作日历 Excel 导入导出应用服务。
 */
public interface ConsoleBusinessCalendarExcelApplicationService {

    /** 导出工作日历配置为 Excel（含日历与假日两个 sheet）。 */
    ResponseEntity<InputStreamResource> exportBusinessCalendars(String tenantId);

    /** 下载空白模板。 */
    ResponseEntity<InputStreamResource> downloadTemplate();

    /** 上传 Excel 并返回 uploadToken。 */
    ConsoleBusinessCalendarExcelUploadResponse upload(MultipartFile file) throws IOException;

    /** 预览解析结果。 */
    ConsoleBusinessCalendarExcelPreviewResponse preview(String uploadToken);

    /** 下载带校验问题明细的预览 workbook。 */
    ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

    /** 确认导入并更新日历配置。 */
    ConsoleBusinessCalendarExcelApplyResponse apply(String uploadToken, BusinessCalendarExcelApplyRequest request);
}
