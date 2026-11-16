package com.example.governance.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reports and dashboards are private to their owner; data permissions are evaluated when run. */
public final class ReportStore {
    public record Report(long id, long datasetId, String name, String chartType, String kind, String configuration) {}
    public record Dashboard(long id, String name, List<Long> reports) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();
    public ReportStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); }
    public long createReport(long owner, long dataset, String name, String chartType, String kind, String config) {
        validateReport(name, chartType, kind, config);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var st = connection.prepareStatement("INSERT INTO reports(owner_id,dataset_id,name,chart_type,query_kind,query_config,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)", new String[]{"id"});
            st.setLong(1, owner); st.setLong(2, dataset); st.setString(3, name); st.setString(4, chartType); st.setString(5, kind); st.setString(6, config); return st;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Report ID unavailable");
        return key.getKey().longValue();
    }
    public boolean updateReport(long owner, long id, long dataset, String name, String chartType, String kind, String config) {
        validateReport(name, chartType, kind, config);
        return jdbc.update("UPDATE reports SET dataset_id=?,name=?,chart_type=?,query_kind=?,query_config=? WHERE id=? AND owner_id=?", dataset, name, chartType, kind, config, id, owner) == 1;
    }
    public boolean deleteReport(long owner, long id) { return jdbc.update("DELETE FROM reports WHERE id=? AND owner_id=?", id, owner) == 1; }
    public Optional<Report> report(long owner, long id) {
        return jdbc.query("SELECT id,dataset_id,name,chart_type,query_kind,query_config FROM reports WHERE owner_id=? AND id=?", (rs, row) -> new Report(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), owner, id).stream().findFirst();
    }
    public List<Report> reports(long owner) {
        return jdbc.query("SELECT id,dataset_id,name,chart_type,query_kind,query_config FROM reports WHERE owner_id=? ORDER BY id", (rs, row) -> new Report(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), owner);
    }
    public long createDashboard(long owner, String name, List<Long> reportIds) {
        name(name);
        validateDashboard(reportIds);
        return tx.execute(status -> {
            for (Long id : reportIds) if (report(owner, id).isEmpty()) throw new IllegalArgumentException("Unknown report");
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> { var st = connection.prepareStatement("INSERT INTO dashboards(owner_id,name,created_at) VALUES (?,?,CURRENT_TIMESTAMP)", new String[]{"id"}); st.setLong(1, owner); st.setString(2, name); return st; }, key);
            if (key.getKey() == null) throw new IllegalStateException("Dashboard ID unavailable");
            long id = key.getKey().longValue();
            for (int i = 0; i < reportIds.size(); i++) jdbc.update("INSERT INTO dashboard_reports(dashboard_id,report_id,ordinal_no) VALUES (?,?,?)", id, reportIds.get(i), i);
            return id;
        });
    }
    public boolean updateDashboard(long owner, long id, String name, List<Long> reportIds) {
        name(name); validateDashboard(reportIds);
        return tx.execute(status -> {
            var existing = jdbc.queryForList("SELECT id FROM dashboards WHERE owner_id=? AND id=? FOR UPDATE", Long.class, owner, id);
            if (existing.isEmpty()) return false;
            for (Long reportId : reportIds) if (report(owner, reportId).isEmpty()) throw new IllegalArgumentException("Unknown report");
            jdbc.update("UPDATE dashboards SET name=? WHERE id=? AND owner_id=?", name, id, owner);
            jdbc.update("DELETE FROM dashboard_reports WHERE dashboard_id=?", id);
            for (int i = 0; i < reportIds.size(); i++) jdbc.update("INSERT INTO dashboard_reports(dashboard_id,report_id,ordinal_no) VALUES (?,?,?)", id, reportIds.get(i), i);
            return true;
        });
    }
    public boolean deleteDashboard(long owner, long id) {
        return tx.execute(status -> {
            var existing = jdbc.queryForList("SELECT id FROM dashboards WHERE owner_id=? AND id=? FOR UPDATE", Long.class, owner, id);
            if (existing.isEmpty()) return false;
            jdbc.update("DELETE FROM dashboard_reports WHERE dashboard_id=?", id);
            return jdbc.update("DELETE FROM dashboards WHERE id=? AND owner_id=?", id, owner) == 1;
        });
    }
    public Optional<Dashboard> dashboard(long owner, long id) {
        var names = jdbc.queryForList("SELECT name FROM dashboards WHERE owner_id=? AND id=?", String.class, owner, id);
        if (names.isEmpty()) return Optional.empty();
        var reports = jdbc.queryForList("SELECT report_id FROM dashboard_reports WHERE dashboard_id=? ORDER BY ordinal_no", Long.class, id);
        return Optional.of(new Dashboard(id, names.getFirst(), reports));
    }
    public List<Dashboard> dashboards(long owner) {
        var ids = jdbc.queryForList("SELECT id FROM dashboards WHERE owner_id=? ORDER BY id", Long.class, owner);
        return ids.stream().map(id -> dashboard(owner, id).orElseThrow()).toList();
    }
    private void validateReport(String name, String chartType, String kind, String config) {
        name(name);
        if (chartType == null || !Set.of("TABLE", "BAR", "LINE").contains(chartType) || kind == null || !Set.of("PREVIEW", "ANALYSIS").contains(kind) || config == null || config.length() > 100000) throw new IllegalArgumentException("Invalid report");
        try { var json = mapper.readTree(config); if (json == null || !json.isObject()) throw new IllegalArgumentException("Invalid report configuration"); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid report configuration"); }
    }
    private static void validateDashboard(List<Long> reportIds) {
        if (reportIds == null || reportIds.isEmpty() || reportIds.size() > 50 || reportIds.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(reportIds).size() != reportIds.size()) throw new IllegalArgumentException("Invalid dashboard reports");
    }
    private static void name(String value) { if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("Invalid name"); }
}
