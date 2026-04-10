package com.example.batch.console.application;

import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件模板 Excel 导入导出应用服务。
 */
public interface ConsoleFileTemplateExcelApplicationService {

    /** 导出文件模板配置为 Excel。 */
    ResponseEntity<InputStreamResource> exportFileTemplates(FileTemplateQueryRequest request);

    /** 下载空白模板。 */
    ResponseEntity<InputStreamResource> downloadTemplate();

    /** 上传 Excel 并返回 uploadToken。 */
    ConsoleFileTemplateExcelUploadResponse upload(MultipartFile file) throws IOException;

    /** 预览上传会话中的行数据与校验问题。 */
    ConsoleFileTemplateExcelPreviewResponse preview(String uploadToken);

    /** 下载带校验问题明细的预览 workbook。 */
    ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

    /** 确认导入并更新模板配置。 */
    ConsoleFileTemplateExcelApplyResponse apply(String uploadToken, FileTemplateExcelApplyRequest request);
}
