package com.example.governance.mysql;
import com.example.governance.realtime.IncrementalSyncExecutor;
import com.example.governance.realtime.RealtimeJobStore;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test",matches="true")
class MySqlRealtimeSettingsTest {
    @Test void updateSettingsPreservesExistingCursorAndRejectsRunning() {
        var db=new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password",""));
        Flyway.configure().dataSource(db).locations("classpath:db/mysql").load().migrate(); var jdbc=new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var store=new RealtimeJobStore(jdbc,new DataSourceTransactionManager(db),(config,cursor)->new IncrementalSyncExecutor.Result(1,new IncrementalSyncExecutor.Cursor("5",1)));
        var mapping=new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",10);
        long id=store.create("job",new RealtimeJobStore.JobConfig(1,1,mapping,5)); var now=Instant.now();
        store.start(id,now); assertTrue(store.poll(id,now)); assertThrows(IllegalStateException.class,()->store.updateSettings(id,10,20));
        store.stop(id); store.updateSettings(id,10,20); var view=store.view(id).orElseThrow();
        assertEquals("5",view.watermark()); assertEquals(1L,view.tie()); assertEquals(1,view.processed()); assertEquals(10,view.intervalSeconds()); assertEquals(20,view.batchSize());
    }
}
