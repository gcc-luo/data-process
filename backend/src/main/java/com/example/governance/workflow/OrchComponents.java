package com.example.governance.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Component registry: the single source of truth for palette entries, port rules and inspector form schemas.
 * Disabled entries are honest "即将支持" placeholders; they can never be saved into an executable definition.
 */
public final class OrchComponents {
    public static final String CATEGORY_SOURCE = "SOURCE";
    public static final String CATEGORY_TRANSFORM = "TRANSFORM";
    public static final String CATEGORY_SINK = "SINK";
    public static final String CATEGORY_CONTROL = "CONTROL";
    public static final String CATEGORY_JOB = "JOB";

    public record Port(String id, String kind, String label, int maxConnections, Map<String, Object> when, boolean dynamic) {}
    public record Component(String type, String name, String category, String engine, String icon, String color,
                            String description, boolean enabled, String note,
                            List<Port> inputPorts, List<Port> outputPorts,
                            List<Map<String, Object>> fields, Map<String, Object> capabilities) {}

    private static final List<Component> REGISTRY = build();

    private OrchComponents() {}

    public static List<Component> all() { return REGISTRY; }
    public static Optional<Component> find(String type) { return REGISTRY.stream().filter(c -> c.type().equals(type)).findFirst(); }
    public static boolean isWrite(Component c) { return Boolean.TRUE.equals(c.capabilities().get("write")); }

    // ---- builder helpers ----
    private static Port in(String id, String kind, int max) { return new Port(id, kind, "输入", max, null, false); }
    private static Port out(String id, String kind) { return new Port(id, kind, "输出", -1, null, false); }
    private static Port dep() { return new Port("dep", "CONTROL", "依赖", -1, null, false); }
    private static Port conditionalOut(String field, String equals) { return new Port("out", "DATA", "输出", -1, Map.of("field", field, "equals", equals), false); }
    private static Port dynamicOut() { return new Port("*", "DATA", "分支出口", -1, null, true); }

    private static Map<String, Object> field(String key, String label, String type, boolean required, String tab) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key); m.put("label", label); m.put("type", type); m.put("required", required); m.put("tab", tab);
        return m;
    }
    private static Map<String, Object> f(String key, String label, String type, boolean required, String tab, Object... kv) {
        var m = field(key, label, type, required, tab);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
    private static List<Map<String, Object>> opts(Object... pairs) {
        var list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i + 1 < pairs.length; i += 2) list.add(Map.of("value", pairs[i], "label", pairs[i + 1]));
        return list;
    }
    private static Map<String, Object> when(String field, Object equals) { return Map.of("field", field, "equals", equals); }

    private static Map<String, Object> caps(boolean write, boolean retry, boolean preview) {
        return Map.of("write", write, "supportsRetry", retry, "supportsPreview", preview);
    }

    private static List<Map<String, Object>> jdbcSourceFields(String dbType) {
        var fields = new ArrayList<Map<String, Object>>();
        fields.add(f("sourceId", "数据源连接", "source", true, "basic", "dbType", dbType, "placeholder", "选择已登记的 " + dbType + " 数据源"));
        fields.add(f("readMode", "读取方式", "select", true, "basic", "options", opts("TABLE", "整表读取", "SQL", "SQL 查询"), "default", "TABLE"));
        fields.add(f("table", "表名", "table", true, "basic", "when", when("readMode", "TABLE"), "placeholder", "可 schema.table"));
        fields.add(f("sql", "查询 SQL", "sql", true, "basic", "when", when("readMode", "SQL"), "placeholder", "SELECT … WHERE update_time >= :last_update_time"));
        fields.add(f("parameters", "参数绑定", "params", false, "basic", "when", when("readMode", "SQL")));
        fields.add(f("incremental", "增量读取（水位）", "switch", false, "advanced", "default", false));
        fields.add(f("watermarkColumn", "水位字段", "text", true, "advanced", "when", when("incremental", true), "placeholder", "updated_at"));
        fields.add(f("tieBreakerColumn", "次级排序键", "text", false, "advanced", "when", when("incremental", true), "placeholder", "id（同时间戳续读）"));
        fields.add(f("watermarkVariable", "水位变量名", "text", true, "advanced", "when", when("incremental", true), "placeholder", "last_update_time"));
        fields.add(f("fetchSize", "游标批大小", "number", false, "advanced", "default", 500, "min", 1, "max", 10000));
        return fields;
    }

    private static List<Map<String, Object>> jdbcSinkFields(String dbType) {
        var fields = new ArrayList<Map<String, Object>>();
        fields.add(f("sourceId", "目标数据源", "source", true, "basic", "dbType", dbType));
        fields.add(f("table", "目标表", "table", true, "basic", "placeholder", "可 schema.table"));
        fields.add(f("writeMode", "写入方式", "select", true, "basic", "options", opts("APPEND", "追加 INSERT", "UPSERT", "按唯一键更新插入"), "default", "APPEND"));
        fields.add(f("keyColumns", "唯一键字段", "columns", true, "basic", "when", when("writeMode", "UPSERT"), "placeholder", "逗号分隔，需与主键/唯一索引一致"));
        fields.add(f("batchSize", "批次大小", "number", false, "advanced", "default", 500, "min", 1, "max", 10000));
        return fields;
    }

    private static Component jdbcSource(String type, String name, String dbType, String icon) {
        return new Component(type, name, CATEGORY_SOURCE, "JDBC", icon, "blue",
                "从 " + name + " 读取行数据批次，支持整表与 SQL 增量读取", true, null,
                List.of(), List.of(out("out", "DATA")), jdbcSourceFields(dbType), caps(false, true, true));
    }
    private static Component jdbcSink(String type, String name, String dbType, String icon) {
        return new Component(type, name, CATEGORY_SINK, "JDBC", icon, "green",
                "批量写入 " + name + "，支持追加与按唯一键 UPSERT", true, null,
                List.of(in("in", "DATA", 1)), List.of(), jdbcSinkFields(dbType), caps(true, true, true));
    }
    private static Component disabled(String type, String name, String category, String icon, String color, String note) {
        return new Component(type, name, category, "CAMEL", icon, color, note, false, "即将支持",
                List.of(), List.of(), List.of(), caps(false, false, false));
    }

    private static List<Component> build() {
        var list = new ArrayList<Component>();
        list.add(jdbcSource("source.mysql", "MySQL", "MYSQL", "mysql"));
        list.add(jdbcSource("source.postgresql", "PostgreSQL", "POSTGRESQL", "postgresql"));
        list.add(jdbcSource("source.oracle", "Oracle", "ORACLE", "oracle"));
        list.add(disabled("source.sqlserver", "SQL Server", CATEGORY_SOURCE, "sqlserver", "blue", "尚未内置 SQL Server 驱动"));
        list.add(disabled("source.kafka", "Kafka 输入", CATEGORY_SOURCE, "kafka", "blue", "需要可测试的 Kafka Broker 与消费运行环境"));
        list.add(new Component("source.http", "HTTP API", CATEGORY_SOURCE, "HTTP", "api", "blue",
                "调用受控 HTTP 接口获取 JSON 数据，支持分页与 JSONPath 提取", true, null,
                List.of(), List.of(out("out", "DATA")), List.of(
                f("method", "请求方法", "select", true, "basic", "options", opts("GET", "GET", "POST", "POST"), "default", "GET"),
                f("url", "请求地址", "text", true, "basic", "placeholder", "https://example.com/api/orders"),
                f("headers", "请求头", "headers", false, "advanced"),
                f("queryParams", "Query 参数", "headers", false, "advanced"),
                f("body", "请求体（JSON）", "textarea", false, "advanced", "when", when("method", "POST")),
                f("extractPath", "数据路径", "text", false, "basic", "placeholder", "data.items（留空取根数组或单对象）"),
                f("pagination", "分页模式", "select", false, "advanced", "options", opts("NONE", "不分页", "PAGE_SIZE", "page/size 分页"), "default", "NONE"),
                f("pageParam", "页码参数名", "text", false, "advanced", "when", when("pagination", "PAGE_SIZE"), "default", "page"),
                f("sizeParam", "页大小参数名", "text", false, "advanced", "when", when("pagination", "PAGE_SIZE"), "default", "size"),
                f("pageSize", "每页条数", "number", false, "advanced", "when", when("pagination", "PAGE_SIZE"), "default", 100, "min", 1, "max", 1000),
                f("startPage", "起始页码", "number", false, "advanced", "when", when("pagination", "PAGE_SIZE"), "default", 1, "min", 0, "max", 100000),
                f("maxPages", "最大页数", "number", false, "advanced", "when", when("pagination", "PAGE_SIZE"), "default", 10, "min", 1, "max", 1000),
                f("timeoutSeconds", "超时（秒）", "number", false, "advanced", "default", 30, "min", 1, "max", 300)
        ), caps(false, true, true)));
        list.add(disabled("source.ftp", "FTP/SFTP", CATEGORY_SOURCE, "ftp", "blue", "文件来源适配尚未实现"));
        list.add(disabled("source.minio", "MinIO/S3", CATEGORY_SOURCE, "minio", "blue", "对象存储适配尚未实现"));

        list.add(new Component("transform.sql", "SQL 脚本", CATEGORY_TRANSFORM, "CAMEL", "sql", "purple",
                "在所选数据库内执行 SELECT（产出数据）或 INSERT/UPDATE/DELETE（终止步骤）；参数使用命名绑定", true, null,
                List.of(dep()), List.of(conditionalOut("sqlMode", "SELECT")), List.of(
                f("sourceId", "数据源连接", "source", true, "basic"),
                f("sqlMode", "执行方式", "select", true, "basic", "options", opts("SELECT", "查询（SELECT）", "DML", "更新（INSERT/UPDATE/DELETE）"), "default", "SELECT"),
                f("sql", "SQL 编辑器", "sql", true, "basic", "placeholder", "SELECT id, order_no FROM t_order WHERE update_time >= :last_update_time"),
                f("parameters", "参数绑定", "params", false, "basic")
        ), caps(true, true, true)));
        list.add(new Component("transform.field-map", "字段映射", CATEGORY_TRANSFORM, "CAMEL", "field-map", "purple",
                "字段重命名、类型转换、默认值填充与转换失败策略", true, null,
                List.of(in("in", "DATA", 1)), List.of(out("out", "DATA")), List.of(
                f("mappings", "字段映射表", "mappings", true, "basic")
        ), caps(false, true, true)));
        list.add(new Component("transform.filter", "数据过滤", CATEGORY_TRANSFORM, "CAMEL", "filter", "purple",
                "按受限表达式保留满足条件的行", true, null,
                List.of(in("in", "DATA", 1)), List.of(out("out", "DATA")), List.of(
                f("expression", "保留条件表达式", "expr", true, "basic", "placeholder", "amount >= 1000 and status = 'PAID'")
        ), caps(false, true, true)));
        list.add(new Component("transform.clean", "数据清洗", CATEGORY_TRANSFORM, "CAMEL", "clean", "purple",
                "按顺序执行 trim、去空值行、去重、正则替换，并输出清洗统计", true, null,
                List.of(in("in", "DATA", 1)), List.of(out("out", "DATA")), List.of(
                f("operations", "清洗步骤", "operations", true, "basic")
        ), caps(false, true, true)));
        list.add(new Component("transform.mask", "字段脱敏", CATEGORY_TRANSFORM, "CAMEL", "mask", "purple",
                "将指定字段整体替换为 ****", true, null,
                List.of(in("in", "DATA", 1)), List.of(out("out", "DATA")), List.of(
                f("field", "脱敏字段", "text", true, "basic")
        ), caps(false, true, true)));
        list.add(new Component("transform.calc", "字段计算", CATEGORY_TRANSFORM, "CAMEL", "calc", "purple",
                "用受限表达式新增计算字段", true, null,
                List.of(in("in", "DATA", 1)), List.of(out("out", "DATA")), List.of(
                f("field", "新字段名", "text", true, "basic"),
                f("expression", "计算表达式", "expr", true, "basic", "placeholder", "ROUND(amount * 100, 0)")
        ), caps(false, true, true)));
        list.add(disabled("transform.python", "Python 脚本", CATEGORY_TRANSFORM, "python", "purple", "需要隔离 Worker 运行环境"));
        list.add(disabled("transform.procedure", "存储过程", CATEGORY_TRANSFORM, "procedure", "purple", "CallableStatement 适配尚未实现"));
        list.add(disabled("transform.error-handler", "异常处理", CATEGORY_TRANSFORM, "error", "orange", "错误端口与 DLQ 尚未实现"));

        list.add(new Component("control.branch", "条件分支", CATEGORY_CONTROL, "CAMEL", "branch", "orange",
                "按规则把每行路由到分支出口，必须有且仅有一个默认出口", true, null,
                List.of(in("in", "DATA", 1)), List.of(dynamicOut()), List.of(
                f("rules", "分支规则", "rules", true, "basic"),
                f("defaultKey", "默认出口标识", "text", true, "basic", "placeholder", "default")
        ), caps(false, true, true)));
        list.add(new Component("control.merge", "数据汇聚", CATEGORY_CONTROL, "CAMEL", "merge", "orange",
                "显式汇聚多个上游分支（UNION 按列名合并，缺失列补空值）", true, null,
                List.of(in("in", "DATA", -1)), List.of(out("out", "DATA")), List.of(
                f("strategy", "汇聚语义", "select", true, "basic", "options", opts("UNION", "UNION 合并"), "default", "UNION")
        ), caps(false, true, true)));

        list.add(jdbcSink("sink.mysql", "MySQL 写入", "MYSQL", "mysql"));
        list.add(jdbcSink("sink.postgresql", "PostgreSQL 写入", "POSTGRESQL", "postgresql"));
        list.add(jdbcSink("sink.oracle", "Oracle 写入", "ORACLE", "oracle"));
        list.add(new Component("sink.http", "API 推送", CATEGORY_SINK, "HTTP", "api", "green",
                "按批将行数据以 JSON 推送到受控 HTTP 接口", true, null,
                List.of(in("in", "DATA", 1)), List.of(), List.of(
                f("url", "推送地址", "text", true, "basic", "placeholder", "https://example.com/api/ingest"),
                f("method", "请求方法", "select", true, "basic", "options", opts("POST", "POST", "PUT", "PUT"), "default", "POST"),
                f("headers", "请求头", "headers", false, "advanced"),
                f("bodyTemplate", "Body 模板", "textarea", false, "advanced", "placeholder", "留空发送行数组；可用 {{rows}} 占位"),
                f("idempotencyKeyHeader", "幂等键请求头", "text", false, "advanced", "placeholder", "Idempotency-Key（重试防重）"),
                f("batchSize", "每批行数", "number", false, "advanced", "default", 100, "min", 1, "max", 1000),
                f("timeoutSeconds", "超时（秒）", "number", false, "advanced", "default", 30, "min", 1, "max", 300)
        ), caps(true, true, false)));
        list.add(disabled("sink.kafka", "Kafka 输出", CATEGORY_SINK, "kafka", "green", "需要可测试的 Kafka Broker"));
        list.add(disabled("sink.file", "文件输出", CATEGORY_SINK, "file", "green", "CSV/JSON 文件适配尚未实现"));
        list.add(disabled("sink.minio", "MinIO/S3 输出", CATEGORY_SINK, "minio", "green", "对象存储适配尚未实现"));
        list.add(disabled("job.flink", "Flink 作业", CATEGORY_JOB, "flink", "orange", "需要 Flink 集群与 REST 适配"));
        return List.copyOf(list);
    }

    /** Active output ports for a node config, honoring conditional and dynamic (branch) ports. */
    public static List<Port> activeOutputPorts(Component c, com.fasterxml.jackson.databind.JsonNode config) {
        var result = new ArrayList<Port>();
        for (Port p : c.outputPorts()) {
            if (p.when() != null) {
                String field = String.valueOf(p.when().get("field"));
                String expected = String.valueOf(p.when().get("equals"));
                String actual = config.path(field).asText("");
                if (!expected.equals(actual)) continue;
            }
            if (p.dynamic()) {
                for (var rule : config.path("rules")) {
                    String key = rule.path("key").asText("");
                    if (!key.isBlank()) result.add(new Port(key, "DATA", rule.path("label").asText(key), -1, null, false));
                }
                String def = config.path("defaultKey").asText("");
                if (!def.isBlank()) result.add(new Port(def, "DATA", "默认", -1, null, false));
                continue;
            }
            result.add(p);
        }
        return result;
    }
}