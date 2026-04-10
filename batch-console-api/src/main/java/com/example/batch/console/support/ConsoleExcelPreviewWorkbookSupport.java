package com.example.batch.console.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 预览阶段使用的 Excel 辅助能力：
 * 生成带 VALIDATION 明细与数据单元格批注的可继续回灌 workbook。
 */
public final class ConsoleExcelPreviewWorkbookSupport {

    private ConsoleExcelPreviewWorkbookSupport() {
    }

    public record WorkbookIssue(String sheetName, int rowNo, String columnName, String message) {
    }

    public static Workbook createWorkbook() {
        return new XSSFWorkbook();
    }

    public static byte[] toBytes(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public static List<WorkbookIssue> expandIssues(String sheetName,
                                                   Integer rowNo,
                                                   List<String> messages,
                                                   List<String> knownColumns) {
        if (rowNo == null || messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> safeColumns = knownColumns == null ? List.of() : knownColumns;
        List<WorkbookIssue> issues = new ArrayList<>();
        for (String message : messages) {
            if (message == null || message.isBlank()) {
                continue;
            }
            List<String> matchedColumns = findMatchedColumns(message, safeColumns);
            if (matchedColumns.isEmpty()) {
                issues.add(new WorkbookIssue(sheetName, rowNo, null, message));
                continue;
            }
            for (String column : matchedColumns) {
                issues.add(new WorkbookIssue(sheetName, rowNo, column, message));
            }
        }
        return issues;
    }

    public static void populateValidationSheet(Workbook workbook, List<WorkbookIssue> issues) {
        Sheet sheet = workbook.getSheet("VALIDATION");
        if (sheet == null) {
            ConsoleExcelStyles.createValidationSheet(workbook);
            sheet = workbook.getSheet("VALIDATION");
        }
        int rowIndex = 1;
        for (WorkbookIssue issue : issues) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(issue.sheetName() == null ? "" : issue.sheetName());
            row.createCell(1).setCellValue(issue.rowNo());
            row.createCell(2).setCellValue(issue.columnName() == null ? "" : issue.columnName());
            row.createCell(3).setCellValue(issue.message() == null ? "" : issue.message());
        }
    }

    public static void addIssueComments(Sheet sheet,
                                        List<String> columns,
                                        List<WorkbookIssue> issues,
                                        int fallbackColumnIndex) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        Map<String, List<String>> messagesByCell = new LinkedHashMap<>();
        for (WorkbookIssue issue : issues) {
            int rowIndex = Math.max(issue.rowNo() - 1, 1);
            int columnIndex = resolveColumnIndex(issue.columnName(), columns, fallbackColumnIndex);
            String key = rowIndex + ":" + columnIndex;
            messagesByCell.computeIfAbsent(key, ignored -> new ArrayList<>()).add(issue.message());
        }

        CreationHelper creationHelper = sheet.getWorkbook().getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        for (Map.Entry<String, List<String>> entry : messagesByCell.entrySet()) {
            String[] coordinates = entry.getKey().split(":");
            int rowIndex = Integer.parseInt(coordinates[0]);
            int columnIndex = Integer.parseInt(coordinates[1]);
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
            }
            Cell cell = row.getCell(columnIndex);
            if (cell == null) {
                cell = row.createCell(columnIndex);
            }
            String commentText = String.join("\n", new LinkedHashSet<>(entry.getValue()));
            ClientAnchor anchor = creationHelper.createClientAnchor();
            anchor.setCol1(columnIndex);
            anchor.setCol2(columnIndex + 4);
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex + 6);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(creationHelper.createRichTextString(commentText));
            comment.setAuthor("batch-console-preview");
            cell.setCellComment(comment);
        }
    }

    public static String previewWorkbookFileName(String originalFileName) {
        String baseName = (originalFileName == null || originalFileName.isBlank()) ? "excel-preview.xlsx" : originalFileName.trim();
        if (baseName.endsWith(".xlsx")) {
            return baseName.substring(0, baseName.length() - 5) + "-preview.xlsx";
        }
        return baseName + "-preview.xlsx";
    }

    private static List<String> findMatchedColumns(String message, List<String> knownColumns) {
        List<String> matches = new ArrayList<>();
        for (String column : sortByLengthDesc(knownColumns)) {
            Pattern pattern = Pattern.compile("(^|[^A-Za-z0-9_])" + Pattern.quote(column) + "([^A-Za-z0-9_]|$)");
            if (pattern.matcher(message).find()) {
                matches.add(column);
            }
        }
        return matches;
    }

    private static List<String> sortByLengthDesc(List<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return sorted;
    }

    private static int resolveColumnIndex(String columnName, List<String> columns, int fallbackColumnIndex) {
        if (columnName != null) {
            int matched = columns.indexOf(columnName);
            if (matched >= 0) {
                return matched;
            }
        }
        return Math.max(fallbackColumnIndex, 0);
    }
}
