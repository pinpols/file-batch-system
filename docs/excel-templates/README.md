# Excel 模板样例

这里放给前端、运营和配置维护人员查看的 Excel 样例文件。所有模板都按 `upload / preview / apply / export` 约束组织，并且 **数据页放在第一个 sheet**，方便直接作为上传样例使用。当前运行时导出的 Excel 也按同一 workbook 结构生成，用户可以导出后修改再导入。

## 文件列表
- [excel-template-gallery.md](./excel-template-gallery.md)
- [file-template-config-template.xlsx](./file-template-config-template.xlsx)
- [file-channel-config-template.xlsx](./file-channel-config-template.xlsx)
- [workflow-maintenance-template.xlsx](./workflow-maintenance-template.xlsx)
- [job-definition-template.xlsx](./job-definition-template.xlsx)
- [alert-routing-notification-policy-template.xlsx](./alert-routing-notification-policy-template.xlsx)

## 说明
- 模板使用 Excel 原生特性，不使用宏。
- 主数据页在第一个 sheet，后面才是说明、字典和校验页。
- 必填列在示例行里会用浅黄底提示；可选列用浅蓝底。
- `DICT` 页提供下拉字典，`VALIDATION` 页用于回填 preview 校验结果。
- `job definition` 和 `workflow definition` 中的系统字段会在说明页注明，不会强迫用户手填。
- `file-template-config-template.xlsx` 已对齐当前导入解析字段；其余模板按系统表结构和观测路由设计对齐。
