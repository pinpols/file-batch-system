package com.example.batch.worker.imports.testing;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Test utility for generating .xlsx file bytes in-memory using Apache POI.
 *
 * <p>Used in import tests that need Excel input fixtures without committing
 * binary files to source control.
 *
 * <p>Usage:
 * <pre>{@code
 * byte[] xlsx = TestExcelFileBuilder.builder()
 *     .sheetName("Sheet1")
 *     .headers(List.of("customerNo", "customerName", "status"))
 *     .row(List.of("C001", "Alice Wang", "ACTIVE"))
 *     .row(List.of("C002", "Bob Li", "INACTIVE"))
 *     .build();
 * }</pre>
 */
public final class TestExcelFileBuilder {

    private String sheetName = "Sheet1";
    private List<String> headers;
    private final List<List<Object>> rows = new ArrayList<>();

    private TestExcelFileBuilder() {}

    public static TestExcelFileBuilder builder() {
        return new TestExcelFileBuilder();
    }

    public TestExcelFileBuilder sheetName(String sheetName) {
        this.sheetName = sheetName;
        return this;
    }

    public TestExcelFileBuilder headers(List<String> headers) {
        this.headers = List.copyOf(headers);
        return this;
    }

    public TestExcelFileBuilder row(List<Object> values) {
        rows.add(List.copyOf(values));
        return this;
    }

    /**
     * Adds rows from a list of maps; column order follows the headers list.
     */
    public TestExcelFileBuilder rows(List<Map<String, Object>> maps) {
        if (headers == null) throw new IllegalStateException("Call headers() before rows(List<Map>)");
        for (Map<String, Object> map : maps) {
            List<Object> vals = headers.stream()
                    .map(h -> map.getOrDefault(h, ""))
                    .toList();
            rows.add(vals);
        }
        return this;
    }

    /**
     * Builds the workbook and returns the raw .xlsx bytes.
     */
    public byte[] build() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            int rowIndex = 0;

            if (headers != null && !headers.isEmpty()) {
                Row headerRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < headers.size(); i++) {
                    headerRow.createCell(i).setCellValue(headers.get(i));
                }
            }

            for (List<Object> rowValues : rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < rowValues.size(); i++) {
                    Cell cell = dataRow.createCell(i);
                    Object val = rowValues.get(i);
                    if (val == null) {
                        cell.setCellValue("");
                    } else if (val instanceof Number num) {
                        cell.setCellValue(num.doubleValue());
                    } else if (val instanceof Boolean bool) {
                        cell.setCellValue(bool);
                    } else {
                        cell.setCellValue(val.toString());
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test Excel file", e);
        }
    }

    // ── convenience factory methods ──────────────────────────────────────────

    /** Builds a customer import .xlsx with standard columns. */
    public static byte[] customerImport(List<Map<String, Object>> customers) {
        return builder()
                .sheetName("Sheet1")
                .headers(List.of("customerNo", "customerName", "customerType",
                        "creditLimit", "currencyCode", "email", "phone",
                        "status", "openDate", "remark"))
                .rows(customers)
                .build();
    }

    /** Minimal single-row .xlsx for a given sheet name and values. */
    public static byte[] singleRow(String sheetName, List<String> headers, List<Object> values) {
        return builder()
                .sheetName(sheetName)
                .headers(headers)
                .row(values)
                .build();
    }
}
