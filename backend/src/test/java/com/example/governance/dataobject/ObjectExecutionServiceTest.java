package com.example.governance.dataobject;

import com.example.governance.assets.DataSourceDefinition;
import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.ExecutionDataSourceProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ObjectExecutionServiceTest {
    @Autowired ObjectExecutionService execution;
    @Autowired ObjectStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectOperationGuard guard;
    @MockitoBean ObjectMetadata metadata;
    @MockitoBean DataSourceStore sources;
    @MockitoBean ExecutionDataSourceProvider connections;
    @Test void persistsPreviewAndRejectsRepeatedExecution() throws Exception {
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9897,'DDL 测试源','MYSQL','MYSQL:8.x','localhost',3306,'test','test','cipher')");
        long directory=store.createDirectory("DDL目录",null);var identity=new ObjectStore.Identity(9897,"PUBLIC",null,"customer_create_test","TABLE");
        when(sources.find(9897)).thenReturn(Optional.of(new DataSourceDefinition("test","MYSQL:8.x","localhost",3306,"test","test",null,Map.of())));
        when(connections.get(9897)).thenReturn(new DriverManagerDataSource("jdbc:h2:mem:object_execution;DB_CLOSE_DELAY=-1","sa",""));
        when(metadata.tables(9897,"PUBLIC",null)).thenReturn(List.of());
        when(metadata.fields(identity)).thenReturn(List.of(new ObjectStore.Field(1,"code","VARCHAR",20,null,"NO","YES",null)));
        var request=new ObjectExecutionService.CreateRequest(directory,identity,"客户","",List.of(new ObjectDialect.Field("code","VARCHAR",20,null,false,true)));
        var preview=execution.previewCreate(request);assertEquals("PREPARED",preview.status());assertTrue(preview.sql().contains("CREATE TABLE"));
        assertThrows(IllegalStateException.class,()->execution.previewCreate(request));
        assertEquals("SUCCEEDED",execution.executeCreate(preview.operationId()).status());
        assertThrows(IllegalStateException.class,()->execution.executeCreate(preview.operationId()));
        assertEquals(1,store.page(directory,"customer_create_test","",1,10).total());
    }
    @Test void executesCommentStepsAndVerifiesMetadata() throws Exception {
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9893,'注释测试源','POSTGRESQL','POSTGRESQL:general','localhost',5432,'db','test','cipher')");
        long directory=store.createDirectory("注释测试",null);var identity=new ObjectStore.Identity(9893,"db","PUBLIC","comment_test","TABLE");
        when(sources.find(9893)).thenReturn(Optional.of(new DataSourceDefinition("test","POSTGRESQL:general","localhost",5432,"db","test",null,Map.of())));
        var ds=new DriverManagerDataSource("jdbc:h2:mem:object_comments;DB_CLOSE_DELAY=-1","sa","");when(connections.get(9893)).thenReturn(ds);when(metadata.tables(9893,"db","PUBLIC")).thenReturn(List.of());
        when(metadata.fields(identity)).thenReturn(List.of(new ObjectStore.Field(1,"code","VARCHAR",20,null,"YES","NO","客户编号")));
        var request=new ObjectExecutionService.CreateRequest(directory,identity,"客户","",List.of(new ObjectDialect.Field("code","VARCHAR",20,null,true,false,"客户编号")));
        var plan=execution.previewCreate(request);assertTrue(plan.sql().contains("COMMENT ON COLUMN"));assertEquals("SUCCEEDED",execution.executeCreate(plan.operationId()).status());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM data_object_operation_steps WHERE operation_id=? AND status='SUCCEEDED'",Integer.class,plan.operationId()));
    }
    @Test void interruptedCreateRequiresUnlockedOperationAndNeverReplaysDdl() throws Exception {
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9892,'恢复测试源','MYSQL','MYSQL:8.x','localhost',3306,'test','test','cipher')");
        long directory=store.createDirectory("恢复测试",null);var identity=new ObjectStore.Identity(9892,"db",null,"missing_test","TABLE");
        when(sources.find(9892)).thenReturn(Optional.of(new DataSourceDefinition("test","MYSQL:8.x","localhost",3306,"test","test",null,Map.of())));when(metadata.tables(9892,"db",null)).thenReturn(List.of());
        var request=new ObjectExecutionService.CreateRequest(directory,identity,"客户","",List.of(new ObjectDialect.Field("code","VARCHAR",20,null,true,false)));
        var plan=execution.previewCreate(request);jdbc.update("UPDATE data_object_operations SET status='EXECUTING' WHERE id=?",plan.operationId());
        try(var held=guard.acquire(plan.operationId())){var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{var failure=pool.submit(()->assertThrows(IllegalStateException.class,()->execution.resolveAbsent(plan.operationId())));failure.get();}finally{pool.shutdownNow();}}
        execution.resolveAbsent(plan.operationId());assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM data_object_operations WHERE id=?",String.class,plan.operationId()));verifyNoInteractions(connections);
    }
}
