package com.example.governance.query;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class QueryConfiguration {
    @Bean DatasetPolicyStore datasetPolicyStore(JdbcTemplate jdbc, PlatformTransactionManager tx) { return new DatasetPolicyStore(jdbc, tx); }
    @Bean QueryLimits queryLimits(@org.springframework.beans.factory.annotation.Value("${governance.query.max-concurrency:8}") int concurrency, @org.springframework.beans.factory.annotation.Value("${governance.query.timeout-seconds:30}") int timeout, @org.springframework.beans.factory.annotation.Value("${governance.query.max-rows:1000}") int maxRows) { return new QueryLimits(concurrency, timeout, maxRows); }
    @Bean AnalysisQueryEngine analysisQueryEngine(QueryLimits limits) { return new AnalysisQueryEngine(limits); }
    @Bean DatasetQueryEngine datasetQueryEngine(QueryLimits limits) { return new DatasetQueryEngine(limits); }
}
