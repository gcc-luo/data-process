package com.example.governance.assets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test",matches="true")
class MySqlRefreshSnapshotTest {
 @Test void readsCurrentSourceCredentialsEvenWhenOuterSnapshotIsOld() throws Exception {
  var db=new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password", ""));
  Flyway.configure().dataSource(db).locations("classpath:db/mysql").load().migrate();
  var jdbc=new JdbcTemplate(db); var manager=new DataSourceTransactionManager(db); var cipher=new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])); var store=new DataSourceStore(jdbc,cipher);
  long id=store.create(new DataSourceDefinition("refresh-snapshot-"+java.util.UUID.randomUUID(),DataSourceDefinition.Type.MYSQL,"localhost",3306,"db","u"),"old");
  String uniqueName = "new-name-"+java.util.UUID.randomUUID();
  var outer=new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  var inner=new TransactionTemplate(manager); inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  outer.executeWithoutResult(status -> {
   assertEquals(3306,store.find(id).orElseThrow().port());
   inner.executeWithoutResult(s -> { try { store.update(id,new DataSourceDefinition(uniqueName,DataSourceDefinition.Type.MYSQL,"localhost",3307,"db","u"),"new"); } catch(Exception e) { throw new IllegalStateException(e); } });
   assertTrue(store.lockSources(java.util.List.of(id)));
   try { var current=store.credentialsForLockedRefresh(id); assertEquals(3307,current.definition().port()); assertEquals("new",current.password()); } catch(Exception e) { throw new IllegalStateException(e); }
  });
 }
 @Test void concurrentRefreshUsesCurrentFieldSnapshotAndIsIdempotent() throws Exception {
  var db=new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password", ""));
  Flyway.configure().dataSource(db).locations("classpath:db/mysql").load().migrate(); var jdbc=new JdbcTemplate(db); var manager=new DataSourceTransactionManager(db);
  var cipher=new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])); var sources=new DataSourceStore(jdbc,cipher); var datasets=new DatasetStore(jdbc,manager);
  long source=sources.create(new DataSourceDefinition("refresh-fields-"+java.util.UUID.randomUUID(),DataSourceDefinition.Type.MYSQL,"localhost",3306,"db","u"),"secret");
  var original=new DatasetStore.Field("id","BIGINT",false); var added=new DatasetStore.Field("extra","VARCHAR",true);
  long dataset=datasets.create(source,"refresh-fields-"+java.util.UUID.randomUUID(),null,null,"t",java.util.List.of(original));
  var outer=new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  var inner=new TransactionTemplate(manager); inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  outer.executeWithoutResult(status -> {
   assertEquals(1,datasets.fields(dataset).size());
   inner.executeWithoutResult(s -> assertEquals(1,datasets.refreshFields(dataset,java.util.List.of(original,added))));
   assertEquals(0,datasets.refreshFields(dataset,java.util.List.of(original,added)));
  });
 }
}
