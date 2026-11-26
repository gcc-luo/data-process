package com.example.governance.masterdata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Bounded XLSX templates, imports, and exports for registered physical tables. */
@Service
public class MasterDataExcelService {
    public record ImportedRow(int rowNumber, Map<String, Object> values) {}
    public record RowError(int rowNumber, String message) {}
    public record ParsedImport(List<ImportedRow> rows, List<RowError> errors, int rejectedRows) {}
    public record ImportSummary(int succeeded, int failed, List<RowError> errors) {}
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 5000;
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_EXPORT_ROWS = 10000;
    private static final long MAX_EXPORT_CELLS = 500000;
    private static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final MasterDataService catalog;
    private final MasterDataRecordService records;

    public MasterDataExcelService(MasterDataService catalog, MasterDataRecordService records) {
        this.catalog = catalog;
        this.records = records;
    }

    public byte[] template(long objectId) throws Exception {
        var detail = catalog.detail(objectId);
        requireTable(detail);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("主数据");
            var header = sheet.createRow(0);
            var style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            var textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            int index = 0;
            for (var field : detail.fields()) {
                Cell cell = header.createCell(index++);
                cell.setCellValue(field.name());
                cell.setCellStyle(style);
                sheet.setColumnWidth(index - 1, Math.min(12000, Math.max(3000, (field.name().length() + 4) * 256)));
                sheet.setDefaultColumnStyle(index - 1, textStyle);
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    public byte[] export(long objectId, MasterDataRecordService.QueryRequest request) throws Exception {
        var detail = catalog.detail(objectId);
        requireTableOrView(detail);
        var first = records.query(objectId, new MasterDataRecordService.QueryRequest(1, 100, request == null ? List.of() : request.filters()));
        if (first.total() > MAX_EXPORT_ROWS) throw new IllegalArgumentException("导出最多支持 " + MAX_EXPORT_ROWS + " 条记录，请缩小查询范围");
        if (first.total() * detail.fields().size() > MAX_EXPORT_CELLS) throw new IllegalArgumentException("当前对象字段较多，请缩小导出记录范围");
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("主数据");
            var header = sheet.createRow(0);
            var style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int column = 0; column < detail.fields().size(); column++) {
                var cell = header.createCell(column);
                cell.setCellValue(detail.fields().get(column).name());
                cell.setCellStyle(style);
                sheet.setColumnWidth(column, Math.min(12000, Math.max(3000, (detail.fields().get(column).name().length() + 4) * 256)));
            }
            int rowIndex = 1;
            int pageCount = (int) Math.ceil(first.total() / 100.0);
            for (int page = 1; page <= Math.max(1, pageCount); page++) {
                var result = page == 1 ? first : records.query(objectId, new MasterDataRecordService.QueryRequest(page, 100, request == null ? List.of() : request.filters()));
                for (Map<String, Object> rowData : result.rows()) {
                    var row = sheet.createRow(rowIndex++);
                    for (int column = 0; column < detail.fields().size(); column++) {
                        Object value = rowData.get(detail.fields().get(column).name());
                        row.createCell(column, CellType.STRING).setCellValue(value == null ? "" : value.toString());
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    public ParsedImport parse(long objectId, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMPORT_BYTES) throw new IllegalArgumentException("文件为空或超过 10 MB 限制");
        var detail = catalog.detail(objectId);
        requireTable(detail);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(workbook instanceof XSSFWorkbook)) throw new IllegalArgumentException("请使用 .xlsx 格式的 Excel 文件");
            if (workbook.getNumberOfSheets() < 1) throw new IllegalArgumentException("Excel 文件没有工作表");
            var sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 1 || sheet.getLastRowNum() > MAX_IMPORT_ROWS)
                throw new IllegalArgumentException("导入行数不能超过 " + MAX_IMPORT_ROWS + " 行");
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() < 1 || header.getLastCellNum() > MAX_COLUMNS)
                throw new IllegalArgumentException("Excel 表头无效或字段数超过 " + MAX_COLUMNS + " 列");
            var known = detail.fields().stream().map(MasterDataService.FieldView::name).collect(java.util.stream.Collectors.toSet());
            var columns = new ArrayList<String>();
            var seen = new java.util.HashSet<String>();
            var formatter = new DataFormatter();
            for (int column = 0; column < header.getLastCellNum(); column++) {
                Cell cell = header.getCell(column);
                rejectFormula(cell, 1);
                String name = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (name.isEmpty()) throw new IllegalArgumentException("第 1 行第 " + (column + 1) + " 列字段名不能为空");
                if (!known.contains(name)) throw new IllegalArgumentException("Excel 中包含未知字段“" + name + "”");
                if (!seen.add(name)) throw new IllegalArgumentException("Excel 字段“" + name + "”重复");
                columns.add(name);
            }
            List<ImportedRow> rows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            int rejectedRows = 0;
            int last = sheet.getLastRowNum();
            for (int index = 1; index <= last; index++) {
                Row row = sheet.getRow(index);
                if (row == null || empty(row, formatter)) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                try {
                    for (int column = 0; column < columns.size(); column++) {
                        Cell cell = row.getCell(column);
                        rejectFormula(cell, index + 1);
                        String raw = cell == null ? "" : formatter.formatCellValue(cell).trim();
                        if (!raw.isEmpty()) values.put(columns.get(column), raw);
                    }
                    for (int column = columns.size(); column < Math.max(0, row.getLastCellNum()); column++) {
                        Cell cell = row.getCell(column);
                        rejectFormula(cell, index + 1);
                        if (cell != null && !formatter.formatCellValue(cell).isBlank())
                            throw new IllegalArgumentException("数据行包含表头之外的内容");
                    }
                    rows.add(new ImportedRow(index + 1, Map.copyOf(values)));
                } catch (IllegalArgumentException ex) {
                    rejectedRows++;
                    if (errors.size() < 100) errors.add(new RowError(index + 1, ex.getMessage()));
                }
            }
            if (rows.isEmpty() && rejectedRows == 0) throw new IllegalArgumentException("Excel 文件没有可导入的数据行");
            return new ParsedImport(List.copyOf(rows), List.copyOf(errors), rejectedRows);
        } catch (EncryptedDocumentException ex) {
            throw new IllegalArgumentException("不支持加密的 Excel 文件", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Excel 文件无法读取，请使用 .xlsx 模板", ex);
        }
    }

    private static boolean empty(Row row, DataFormatter formatter) {
        for (int column = 0; column < Math.min(MAX_COLUMNS, Math.max(0, row.getLastCellNum())); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && (cell.getCellType() == CellType.FORMULA || (cell.getCellType() != CellType.BLANK && !formatter.formatCellValue(cell).isBlank()))) return false;
        }
        return true;
    }
    private static void rejectFormula(Cell cell, int row) {
        if (cell != null && cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("第 " + row + " 行包含公式，请改为普通值");
    }
    private static void requireTable(MasterDataService.ObjectDetail detail) {
        if (!"TABLE".equals(detail.object().identity().type())) throw new IllegalStateException("仅数据表支持导入模板");
    }
    private static void requireTableOrView(MasterDataService.ObjectDetail detail) {
        if (!java.util.Set.of("TABLE", "VIEW").contains(detail.object().identity().type())) throw new IllegalStateException("对象不可导出");
    }
}
