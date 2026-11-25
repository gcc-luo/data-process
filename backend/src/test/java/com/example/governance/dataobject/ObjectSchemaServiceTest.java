package com.example.governance.dataobject;
import com.example.governance.assets.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ObjectSchemaServiceTest {
 @Autowired ObjectSchemaService schema; @Autowired ObjectStore store; @Autowired JdbcTemplate jdbc;
 @MockitoBean ObjectMetadata metadata; @MockitoBean DataSourceStore sources; @MockitoBean ExecutionDataSourceProvider connections;
 @Test void stalePreviewDoesNotExecuteAndReleasesIdentity() throws Exception {
  jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9896,'结构测试源','POSTGRESQL','POSTGRESQL:general','localhost',5432,'db','test','cipher')");
  long dir=store.createDirectory("结构测试",null);var identity=new ObjectStore.Identity(9896,"db","public","customers","TABLE");
  var old=List.of(new ObjectStore.Field(1,"code","VARCHAR",20,null,"NO","YES",null));
  long object=store.register(dir,identity,"客户","",old);when(sources.find(9896)).thenReturn(Optional.of(new DataSourceDefinition("test","POSTGRESQL:general","localhost",5432,"db","test",null,Map.of())));
  when(metadata.fields(identity)).thenReturn(old,List.of(new ObjectStore.Field(1,"code","VARCHAR",25,null,"NO","YES",null)));
  var target=List.of(new ObjectDialect.Field("code","VARCHAR",20,null,false,true),new ObjectDialect.Field("note","VARCHAR",100,null,true,false));
  var preview=schema.preview(object,new ObjectSchemaService.Request(target));assertEquals("PREPARED",preview.status());
  assertThrows(IllegalStateException.class,()->schema.execute(preview.operationId()));assertEquals("STALE",jdbc.queryForObject("SELECT status FROM data_object_operations WHERE id=?",String.class,preview.operationId()));verifyNoInteractions(connections);
 }
 @Test void mysqlModificationDoesNotDiscardColumnProperties() throws Exception {
  jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9895,'MySQL 结构测试源','MYSQL','MYSQL:8.x','localhost',3306,'db','test','cipher')");
  long dir=store.createDirectory("MySQL结构测试",null);var identity=new ObjectStore.Identity(9895,"db","","customers","TABLE");var old=List.of(new ObjectStore.Field(1,"name","VARCHAR",20,null,"YES","NO","original comment"));long object=store.register(dir,identity,"客户","",old);
  when(sources.find(9895)).thenReturn(Optional.of(new DataSourceDefinition("test","MYSQL:8.x","localhost",3306,"db","test",null,Map.of())));when(metadata.fields(identity)).thenReturn(old);
  var dataSource=mock(javax.sql.DataSource.class);var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);var result=mock(java.sql.ResultSet.class);when(connections.get(9895)).thenReturn(dataSource);when(dataSource.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true,false,true,false);when(result.getString(1)).thenReturn("name");when(result.getString(2)).thenReturn("O'Reilly");when(result.getString(3)).thenReturn("", "auto_increment");when(result.getString(4)).thenReturn("original comment");when(result.getString(5)).thenReturn("utf8mb4");when(result.getString(6)).thenReturn("utf8mb4_bin");
  var target=List.of(new ObjectDialect.Field("name","VARCHAR",10,null,true,false));var plan=schema.preview(object,new ObjectSchemaService.Request(target));String sql=plan.changes().getFirst().sql();assertTrue(sql.contains("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));assertTrue(sql.contains("DEFAULT 'O''Reilly'"));assertTrue(sql.contains("COMMENT 'original comment'"));schema.cancel(plan.operationId());assertThrows(IllegalArgumentException.class,()->schema.preview(object,new ObjectSchemaService.Request(target)));
 }
 @Test void executesAndPersistsVerifiedSchema() throws Exception {
  jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9894,'发布测试源','POSTGRESQL','POSTGRESQL:general','localhost',5432,'db','test','cipher')");
  long dir=store.createDirectory("发布结构",null);var identity=new ObjectStore.Identity(9894,"db","PUBLIC","schema_test","TABLE");
  var old=List.of(new ObjectStore.Field(1,"code","VARCHAR",20,null,"NO","YES",null));var updated=List.of(old.getFirst(),new ObjectStore.Field(2,"note","VARCHAR",100,null,"YES","NO","备注"));
  long object=store.register(dir,identity,"客户","",old);when(sources.find(9894)).thenReturn(Optional.of(new DataSourceDefinition("test","POSTGRESQL:general","localhost",5432,"db","test",null,Map.of())));
  var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:schema_execution;DB_CLOSE_DELAY=-1","sa","");new JdbcTemplate(ds).execute("CREATE TABLE PUBLIC.\"schema_test\" (\"code\" VARCHAR(20) PRIMARY KEY)");when(connections.get(9894)).thenReturn(ds);when(metadata.fields(identity)).thenReturn(old,old,updated);
  var target=List.of(new ObjectDialect.Field("code","VARCHAR",20,null,false,true),new ObjectDialect.Field("note","VARCHAR",100,null,true,false,"备注"));var plan=schema.preview(object,new ObjectSchemaService.Request(target));assertEquals(2,plan.changes().size());assertEquals("SUCCEEDED",schema.execute(plan.operationId()).status());assertEquals(2,store.fields(object).size());
  store.delete(object);assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM data_object_operations WHERE id=?",String.class,plan.operationId()));
 }
 @Test void interruptedSchemaCanBeInspectedWithoutReexecution() throws Exception {
  jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9891,'中断结构源','POSTGRESQL','POSTGRESQL:general','localhost',5432,'db','test','cipher')");
  long dir=store.createDirectory("中断结构",null);var identity=new ObjectStore.Identity(9891,"db","public","recover_test","TABLE");var fields=List.of(new ObjectStore.Field(1,"name","VARCHAR",20,null,"YES","NO",null));long object=store.register(dir,identity,"客户","",fields);
  when(sources.find(9891)).thenReturn(Optional.of(new DataSourceDefinition("test","POSTGRESQL:general","localhost",5432,"db","test",null,Map.of())));when(metadata.fields(identity)).thenReturn(fields);
  var target=List.of(new ObjectDialect.Field("name","VARCHAR",20,null,true,false),new ObjectDialect.Field("note","VARCHAR",50,null,true,false));var plan=schema.preview(object,new ObjectSchemaService.Request(target));jdbc.update("UPDATE data_object_operations SET status='EXECUTING' WHERE id=?",plan.operationId());
  assertEquals(fields,schema.inspect(plan.operationId()));assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM data_object_operations WHERE id=?",String.class,plan.operationId()));schema.acknowledge(plan.operationId());assertEquals("RECONCILED",jdbc.queryForObject("SELECT status FROM data_object_operations WHERE id=?",String.class,plan.operationId()));verifyNoInteractions(connections);
 }
}
