package com.example.governance.masterdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** HTTP boundary for separately authorized global master-data operations. */
@RestController
@RequestMapping("/api/master-data")
public class MasterDataController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<MasterDataRecordService.Filter>> FILTERS = new TypeReference<>() {};
    private final MasterDataService catalog;
    private final MasterDataRecordService records;
    private final MasterDataHistoryStore history;
    private final MasterDataExcelService excel;
    private final com.example.governance.security.AuditLog audit;
    private final ObjectMapper json;

    public MasterDataController(MasterDataService catalog, MasterDataRecordService records, MasterDataHistoryStore history,
                                MasterDataExcelService excel, com.example.governance.security.AuditLog audit, ObjectMapper json) {
        this.catalog = catalog;
        this.records = records;
        this.history = history;
        this.excel = excel;
        this.audit = audit;
        this.json = json;
    }

    @GetMapping("/tree") public MasterDataService.Tree tree() { return catalog.tree(); }

    @GetMapping("/objects/{id}") public MasterDataService.ObjectDetail detail(@PathVariable long id) {
        try { return catalog.detail(id); }
        catch (IllegalArgumentException | IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取数据对象字段，请检查数据源连接"); }
    }

    @PostMapping("/objects/{id}/query") public MasterDataRecordService.Page query(@PathVariable long id, @RequestBody MasterDataRecordService.QueryRequest request) {
        try { return records.query(id, request); }
        catch (IllegalArgumentException | IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "读取主数据失败，请检查数据源连接"); }
    }

    @PostMapping("/objects/{id}/records") public MasterDataRecordService.Mutation create(@PathVariable long id, @RequestBody MasterDataRecordService.RecordInput input, Authentication auth) {
        Map<String, Object> key = Map.of();
        long event = prepare(id, "CREATE", key, auth);
        MasterDataRecordService.Mutation result;
        try { result = records.create(id, input); }
        catch (Exception ex) { fail(event, key, ex, auth, id, "master_data.create"); throw apiError(ex); }
        complete(event, result, auth, id, "master_data.create");
        return result;
    }

    @PutMapping("/objects/{id}/records") public MasterDataRecordService.Mutation update(@PathVariable long id, @RequestBody MasterDataRecordService.RecordInput input, Authentication auth) {
        Map<String, Object> key = input == null ? Map.of() : input.key();
        long event = prepare(id, "UPDATE", key, auth);
        MasterDataRecordService.Mutation result;
        try { result = records.update(id, input); }
        catch (Exception ex) { fail(event, key, ex, auth, id, "master_data.update"); throw apiError(ex); }
        complete(event, result, auth, id, "master_data.update");
        return result;
    }

    @DeleteMapping("/objects/{id}/records") public MasterDataRecordService.Mutation delete(@PathVariable long id, @RequestBody Map<String, Object> key, Authentication auth) {
        long event = prepare(id, "DELETE", key, auth);
        MasterDataRecordService.Mutation result;
        try { result = records.delete(id, key); }
        catch (Exception ex) { fail(event, key, ex, auth, id, "master_data.delete"); throw apiError(ex); }
        complete(event, result, auth, id, "master_data.delete");
        return result;
    }

    @GetMapping("/objects/{id}/template") public ResponseEntity<byte[]> template(@PathVariable long id, Authentication auth) {
        try {
            byte[] content = excel.template(id);
            audit.success(auth, "master_data.template", "master_data_record", Long.toString(id));
            return download(content, "主数据导入模板-" + id + ".xlsx");
        } catch (Exception ex) {
            audit.failure(auth, "master_data.template", "master_data_record", Long.toString(id), message(ex));
            throw apiError(ex);
        }
    }

    @GetMapping("/objects/{id}/export") public ResponseEntity<byte[]> export(@PathVariable long id,
            @RequestParam(required = false) String filters, Authentication auth) {
        try {
            var request = new MasterDataRecordService.QueryRequest(1, 100, parseFilters(filters));
            byte[] content = excel.export(id, request);
            audit.success(auth, "master_data.export", "master_data_record", Long.toString(id));
            return download(content, "主数据-" + id + ".xlsx");
        } catch (Exception ex) {
            audit.failure(auth, "master_data.export", "master_data_record", Long.toString(id), message(ex));
            throw apiError(ex);
        }
    }

    @PostMapping(value = "/objects/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MasterDataExcelService.ImportSummary importFile(@PathVariable long id, @RequestPart("file") MultipartFile file, Authentication auth) {
        try {
            if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 Excel 文件");
            if (file.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("文件超过 10 MB 限制");
            var parsed = excel.parse(id, file.getBytes());
            var object = catalog.detail(id);
            int succeeded = 0;
            int failed = parsed.rejectedRows();
            List<MasterDataExcelService.RowError> errors = new ArrayList<>(parsed.errors());
            for (var row : parsed.rows()) {
                long event;
                try { event = history.prepare(object, "IMPORT", Map.of(), auth); }
                catch (RuntimeException ex) {
                    failed++;
                    addError(errors, row.rowNumber(), "无法创建版本记录，未执行导入");
                    continue;
                }
                boolean committed = false;
                try {
                    var mutation = records.create(id, new MasterDataRecordService.RecordInput(null, row.values()));
                    committed = true;
                    history.succeed(event, mutation.key(), mutation.before(), mutation.after());
                    succeeded++;
                } catch (Exception ex) {
                    failed++;
                    if (committed) addError(errors, row.rowNumber(), "记录已写入，但版本历史状态未能确认，请刷新并核对历史");
                    else {
                        try { history.fail(event, Map.of(), message(ex)); }
                        catch (RuntimeException historyFailure) { ex.addSuppressed(historyFailure); }
                        addError(errors, row.rowNumber(), message(ex));
                    }
                }
            }
            String summary = "成功 " + succeeded + " 条，失败 " + failed + " 条";
            if (failed == 0) audit.success(auth, "master_data.import", "master_data_record", Long.toString(id), summary);
            else audit.failure(auth, "master_data.import", "master_data_record", Long.toString(id), summary);
            return new MasterDataExcelService.ImportSummary(succeeded, failed, List.copyOf(errors));
        } catch (Exception ex) {
            audit.failure(auth, "master_data.import", "master_data_record", Long.toString(id), message(ex));
            throw apiError(ex);
        }
    }

    @GetMapping("/objects/{id}/history") public MasterDataHistoryStore.HistoryPage history(@PathVariable long id,
            @RequestParam(required = false) String key, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        try {
            Map<String, Object> parsedKey = key == null || key.isBlank() ? null : parseMap(key);
            var result = history.list(id, parsedKey, page, size);
            audit.success(auth, "master_data.history", "master_data_record", Long.toString(id));
            return result;
        } catch (Exception ex) {
            audit.failure(auth, "master_data.history", "master_data_record", Long.toString(id), message(ex));
            throw apiError(ex);
        }
    }

    @GetMapping("/history/{historyId}") public MasterDataHistoryStore.Entry historyDetail(@PathVariable long historyId, Authentication auth) {
        try {
            var entry = history.find(historyId);
            audit.success(auth, "master_data.history", "master_data_record", Long.toString(entry.objectId()));
            return entry;
        } catch (Exception ex) {
            audit.failure(auth, "master_data.history", "master_data_record", Long.toString(historyId), message(ex));
            throw apiError(ex);
        }
    }

    private long prepare(long objectId, String operation, Map<String, Object> key, Authentication auth) {
        try { return history.prepare(catalog.detail(objectId), operation, key, auth); }
        catch (Exception ex) {
            String action = "CREATE".equals(operation) ? "master_data.create" : "UPDATE".equals(operation) ? "master_data.update" : "master_data.delete";
            audit.failure(auth, action, "master_data_record", Long.toString(objectId), message(ex));
            throw apiError(ex);
        }
    }
    private void complete(long event, MasterDataRecordService.Mutation result, Authentication auth, long objectId, String action) {
        try {
            history.succeed(event, result.key(), result.before(), result.after());
            audit.success(auth, action, "master_data_record", Long.toString(objectId), "影响 " + result.affected() + " 条记录");
        } catch (RuntimeException ex) {
            audit.failure(auth, action, "master_data_record", Long.toString(objectId), "记录已提交，但版本历史状态未能确认");
            throw ex;
        }
    }
    private void fail(long event, Map<String, Object> key, Exception error, Authentication auth, long objectId, String action) {
        try { history.fail(event, key, message(error)); }
        catch (RuntimeException historyFailure) { error.addSuppressed(historyFailure); }
        audit.failure(auth, action, "master_data_record", Long.toString(objectId), message(error));
    }
    private Map<String, Object> parseMap(String value) {
        try { return json.readValue(value, MAP); }
        catch (Exception ex) { throw new IllegalArgumentException("主键格式无效", ex); }
    }
    private List<MasterDataRecordService.Filter> parseFilters(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, FILTERS); }
        catch (Exception ex) { throw new IllegalArgumentException("筛选条件格式无效", ex); }
    }
    private static ResponseEntity<byte[]> download(byte[] content, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(XLSX)).contentLength(content.length).body(content);
    }
    private static void addError(List<MasterDataExcelService.RowError> errors, int row, String message) {
        if (errors.size() < 100) errors.add(new MasterDataExcelService.RowError(row, message));
    }
    private static String message(Throwable error) {
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            String safe = error.getMessage();
            return safe == null || safe.isBlank() ? "主数据操作失败" : safe;
        }
        if (error instanceof ResponseStatusException status && status.getReason() != null) return status.getReason();
        return "主数据操作失败，请检查数据源连接或平台历史存储";
    }
    private static RuntimeException apiError(Exception error) {
        if (error instanceof IllegalArgumentException invalid) return invalid;
        if (error instanceof IllegalStateException conflict) return conflict;
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "主数据操作失败，请检查数据源连接或平台历史存储");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }
}
