package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Controlled HTTP JSON source: guard-checked targets, no redirects, page/size pagination, dot-path extraction. */
public final class HttpSourceFetcher {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final HttpTargetGuard guard;

    public HttpSourceFetcher(HttpTargetGuard guard) { this.guard = guard; }

    public List<Map<String, Object>> fetch(JsonNode config, RunCancelContext cancel) throws Exception {
        String method = config.path("method").asText("GET");
        String base = config.path("url").asText("");
        int timeout = config.path("timeoutSeconds").asInt(30);
        String pagination = config.path("pagination").asText("NONE");
        int maxPages = "PAGE_SIZE".equals(pagination) ? config.path("maxPages").asInt(10) : 1;
        int pageSize = config.path("pageSize").asInt(100);
        int page = config.path("startPage").asInt(1);
        String pageParam = config.path("pageParam").asText("page");
        String sizeParam = config.path("sizeParam").asText("size");
        var rows = new ArrayList<Map<String, Object>>();
        for (int p = 0; p < maxPages; p++) {
            if (cancel.isCancelled()) throw new InterruptedException("手动停止");
            String url = base;
            if ("PAGE_SIZE".equals(pagination)) {
                String sep = url.contains("?") ? "&" : "?";
                url = url + sep + enc(pageParam) + "=" + (page + p) + "&" + enc(sizeParam) + "=" + pageSize;
            }
            url = appendQueryParams(url, config.path("queryParams"));
            URI uri = URI.create(url);
            guard.check(uri);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
                    .header("Accept", "application/json");
            for (JsonNode h : config.path("headers")) {
                String name = h.path("name").asText("");
                if (!name.isBlank()) builder.header(name, h.path("value").asText(""));
            }
            if ("POST".equals(method)) {
                String body = config.path("body").asText("");
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body.isEmpty() ? "{}" : body, StandardCharsets.UTF_8));
            } else builder.GET();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
                throw new IllegalStateException("HTTP 来源返回状态 " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = extract(root, config.path("extractPath").asText(""));
            int before = rows.size();
            if (data.isArray()) {
                for (JsonNode item : data) rows.add(toRow(item));
            } else if (data.isObject()) {
                rows.add(toRow(data));
            } else if (!data.isNull() && !data.isMissingNode()) {
                throw new IllegalStateException("HTTP 来源数据路径必须指向对象或数组");
            }
            if ("PAGE_SIZE".equals(pagination)) {
                int fetched = rows.size() - before;
                if (fetched < pageSize) break;
            } else break;
        }
        return rows;
    }

    static String appendQueryParams(String url, JsonNode params) {
        if (!params.isArray() || params.isEmpty()) return url;
        var sb = new StringBuilder(url);
        for (JsonNode p : params) {
            String name = p.path("name").asText("");
            if (name.isBlank()) continue;
            sb.append(url.contains("?") || sb.indexOf("?") >= 0 ? '&' : '?').append(enc(name)).append('=').append(enc(p.path("value").asText("")));
        }
        return sb.toString();
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    static JsonNode extract(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (part.isBlank()) continue;
            if (part.matches("\\d+")) current = current.path(Integer.parseInt(part));
            else current = current.path(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toRow(JsonNode item) {
        if (!item.isObject()) throw new IllegalStateException("HTTP 来源数组元素必须是 JSON 对象");
        return mapper.convertValue(item, LinkedHashMap.class);
    }
}