package com.example.governance.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retention policy: purge audit rows older than the configured window and record the purge itself. */
@Component
public class AuditRetention {
    private final JdbcTemplate jdbc;
    private final int days;

    public AuditRetention(JdbcTemplate jdbc, @Value("${governance.audit-retention-days:180}") int days) { this.jdbc = jdbc; this.days = days; }

    @Scheduled(cron = "${governance.audit-retention-cron:0 30 3 * * *}")
    public void purge() {
        if (days <= 0) return;
        int removed = jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?", Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)));
        if (removed > 0)
            jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result,ip,user_agent,message) VALUES (NULL,CURRENT_TIMESTAMP,'audit.purge','audit','retention','SUCCESS','','',?)",
                    "清理过期审计 " + removed + " 条（保留 " + days + " 天）");
    }
}
