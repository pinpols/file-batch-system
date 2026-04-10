package com.example.batch.console.application;

import com.example.batch.console.web.request.TenantQuotaPolicyExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 租户配额策略 Excel 导入导出应用服务。
 */
public interface ConsoleTenantQuotaPolicyExcelApplicationService {

    /** 导出租户配额策略配置为 Excel。 */
    ResponseEntity<InputStreamResource> exportQuotaPolicies(String tenantId, String policyCode, Boolean enabled);

    /** 下载空白模板。 */
    ResponseEntity<InputStreamResource> downloadTemplate();

    /** 上传 Excel 并返回 uploadToken。 */
    ConsoleTenantQuotaPolicyExcelUploadResponse upload(MultipartFile file) throws IOException;

    /** 预览解析结果。 */
    ConsoleTenantQuotaPolicyExcelPreviewResponse preview(String uploadToken);

    /** 下载带校验问题明细的预览 workbook。 */
    ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

    /** 确认导入并更新配额策略配置。 */
    ConsoleTenantQuotaPolicyExcelApplyResponse apply(String uploadToken, TenantQuotaPolicyExcelApplyRequest request);
}
