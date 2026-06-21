package com.example.batch.console.support.excel;

import java.util.Locale;
import lombok.Builder;
import org.springframework.context.MessageSource;

/**
 * Excel 下拉数据校验参数封装。封装 6 个非定位参数（values/promptTitle/promptText/maxRow/messageSource/locale）， 让 {@link
 * ConsoleExcelStyles#addDropdownValidation(org.apache.poi.ss.usermodel.Sheet, int,
 * DropdownValidationSpec)} 主签名退回到 ≤6 参，符合 CLAUDE.md §方法参数约束。
 *
 * <p>promptTitle / promptText 以 {@code "excel."} 开头视为 i18n key，writer 传入 {@link MessageSource} +
 * {@link Locale} 时按 locale 翻译；不传则原样落 Excel 提示框。调用方应使用 {@link #builder()}， null/0 字段不显式
 * set，靠默认值回退（CLAUDE.md §调用方约束）。
 */
@Builder
public record DropdownValidationSpec(
    String[] values,
    String promptTitle,
    String promptText,
    int maxRow,
    MessageSource messageSource,
    Locale locale) {}
