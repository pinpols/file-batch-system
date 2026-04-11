package com.example.batch.console.application;

import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.AlertRoutingExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelUploadResponse;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 告警路由 Excel 导入导出应用服务。 */
public interface ConsoleAlertRoutingExcelApplicationService {

    /** 导出告警路由配置为 Excel。 */
    ResponseEntity<InputStreamResource> exportAlertRoutings(AlertRoutingQueryRequest request);

    /** 下载空白模板。 */
    ResponseEntity<InputStreamResource> downloadTemplate();

    /** 上传 Excel 并返回 uploadToken。 */
    ConsoleAlertRoutingExcelUploadResponse upload(MultipartFile file) throws IOException;

    /** 预览解析结果。 */
    ConsoleAlertRoutingExcelPreviewResponse preview(String uploadToken);

    /** 下载带校验问题明细的预览 workbook。 */
    ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

    /** 确认导入并更新告警路由配置。 */
    ConsoleAlertRoutingExcelApplyResponse apply(
            String uploadToken, AlertRoutingExcelApplyRequest request);
}
