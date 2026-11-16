package com.example.governance.realtime;

import com.example.governance.assets.ExecutionDataSourceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class RealtimeConfiguration {
    @Bean(destroyMethod = "close") IncrementalSyncExecutor incrementalSyncExecutor() throws Exception { return new IncrementalSyncExecutor(); }
    @Bean RealtimeJobStore realtimeJobStore(JdbcTemplate jdbc, PlatformTransactionManager tx, ExecutionDataSourceProvider sources, IncrementalSyncExecutor executor) {
        return new RealtimeJobStore(jdbc, tx, (config, cursor) -> executor.poll(sources.get(config.sourceId()), sources.get(config.targetId()), config.mapping(), cursor));
    }
    @Bean RealtimePoller realtimePoller(RealtimeJobStore jobs) { return new RealtimePoller(jobs); }
}
