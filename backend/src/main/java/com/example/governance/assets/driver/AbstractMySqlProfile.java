package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class AbstractMySqlProfile implements DatabaseProfile {
    @Override public String type() { return "MYSQL"; }
    @Override public int defaultPort() { return 3306; }
    @Override public String jdbcUrl(DataSourceDefinition d) { return "jdbc:mysql://" + d.host() + ":" + d.port() + "/" + d.database(); }
    @Override public Map<String, String> defaultProperties() { return Map.of("connectTimeout", "5000", "socketTimeout", "10000"); }
    @Override public List<ExtraField> extraFields() { return List.of(); }
    final Map<String, ParamSpec> paramsWith(ParamSpec... extra) {
        var params = new LinkedHashMap<String, ParamSpec>();
        params.put("useSSL", ParamSpec.bool("useSSL", "使用 SSL", "true/false"));
        params.put("useUnicode", ParamSpec.bool("useUnicode", "使用 Unicode", "true/false"));
        params.put("characterEncoding", ParamSpec.enumOf("characterEncoding", "字符编码", List.of("UTF-8", "GBK", "GB18030", "latin1"), "编码名"));
        params.put("serverTimezone", ParamSpec.zone("serverTimezone", "服务器时区", "如 Asia/Shanghai 或 +08:00"));
        params.put("connectTimeout", ParamSpec.millis("connectTimeout", "连接超时(ms)", "0..3600000"));
        params.put("socketTimeout", ParamSpec.millis("socketTimeout", "读取超时(ms)", "0..3600000"));
        for (ParamSpec spec : extra) params.put(spec.key(), spec);
        return Map.copyOf(params);
    }
}
