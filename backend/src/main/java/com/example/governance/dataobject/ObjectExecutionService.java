package com.example.governance.dataobject;

import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.ExecutionDataSourceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Persists DDL intent before touching the remote database; never replays ambiguous execution. */
@Service
public class ObjectExecutionService {
    public record CreateRequest(long directoryId,ObjectStore.Identity identity,String name,String description,List<ObjectDialect.Field> fields) {}
    public record Preview(String operationId,ObjectStore.Identity identity,String sql,String status) {}
    public record PendingOperation(String operationId,ObjectStore.Identity identity,String name,String status,String error,String createdAt) {}
    private record Pending(String id,String type,String status,String target,String sql) {}
    private final JdbcTemplate jdbc;private final ObjectStore store;private final ObjectMetadata metadata;private final DataSourceStore sources;private final ExecutionDataSourceProvider connections;private final ObjectMapper mapper;private final TransactionTemplate tx;private final ObjectOperationGuard guard;
    public ObjectExecutionService(JdbcTemplate jdbc,ObjectStore store,ObjectMetadata metadata,DataSourceStore sources,ExecutionDataSourceProvider connections,ObjectMapper mapper,PlatformTransactionManager manager,ObjectOperationGuard guard){this.jdbc=jdbc;this.store=store;this.metadata=metadata;this.sources=sources;this.connections=connections;this.mapper=mapper;this.tx=new TransactionTemplate(manager);this.guard=guard;}
    public Preview previewCreate(CreateRequest request) throws Exception {
        if(request==null||request.identity()==null||!"TABLE".equals(request.identity().type())||request.directoryId()<1||request.name()==null||request.name().isBlank()||request.name().length()>200||request.description()!=null&&request.description().length()>1000)throw new IllegalArgumentException("对象信息无效");
        if(jdbc.queryForList("SELECT id FROM object_directories WHERE id=?",Long.class,request.directoryId()).isEmpty())throw new IllegalArgumentException("目录不存在");
        var identity=request.identity();metadata.validateTarget(identity);var source=sources.find(identity.sourceId()).orElseThrow(()->new IllegalArgumentException("数据源不存在"));
        String qualifier="MYSQL".equals(source.type())?identity.catalog():identity.schema();if(qualifier==null||qualifier.isBlank())throw new IllegalArgumentException("请选择目标数据库或模式");
        List<String> statements=ObjectDialect.forProfile(source.profileId()).createStatements(new ObjectDialect.Table(qualifier,identity.table(),request.fields()));String sql=String.join(";\n",statements);
        if(exists(identity))throw new IllegalStateException("目标物理表已存在，请使用注册功能");String hash=ObjectStore.identityHash(identity), id=UUID.randomUUID().toString(), json=mapper.writeValueAsString(request);
        try{tx.executeWithoutResult(status->{jdbc.update("INSERT INTO data_object_operations(id,source_id,identity_hash,active_identity,operation_type,status,target_json) VALUES (?,?,?,?,?,?,?)",id,identity.sourceId(),hash,hash,"CREATE","PREPARED",json);for(int i=0;i<statements.size();i++)jdbc.update("INSERT INTO data_object_operation_steps(operation_id,step_number,statement_text,status) VALUES (?,?,?,'PENDING')",id,i+1,statements.get(i));});}catch(DuplicateKeyException e){throw new IllegalStateException("该物理对象已有待处理操作");}
        return new Preview(id,identity,sql,"PREPARED");
    }
    private boolean exists(ObjectStore.Identity identity) throws Exception {return metadata.tables(identity.sourceId(),identity.catalog(),identity.schema()).stream().anyMatch(t->Objects.equals(t.name(),identity.table())&&Objects.equals(t.catalog()==null?"":t.catalog(),identity.catalog()==null?"":identity.catalog())&&Objects.equals(t.schema()==null?"":t.schema(),identity.schema()==null?"":identity.schema()));}
    private Pending get(String id){return jdbc.query("SELECT o.id,o.operation_type,o.status,o.target_json,s.statement_text FROM data_object_operations o JOIN data_object_operation_steps s ON s.operation_id=o.id AND s.step_number=1 WHERE o.id=?",(r,n)->new Pending(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("操作不存在"));}
    public Preview executeCreate(String id) throws Exception {try(var lease=guard.acquire(id)){return executeCreateLocked(id);}}
    private Preview executeCreateLocked(String id) throws Exception {
        Pending p=get(id);if(!"CREATE".equals(p.type())||!"PREPARED".equals(p.status()))throw new IllegalStateException("操作状态已变化，请刷新");CreateRequest request=mapper.readValue(p.target(),CreateRequest.class);var source=sources.find(request.identity().sourceId()).orElseThrow(()->new IllegalArgumentException("数据源不存在"));metadata.validateTarget(request.identity());String qualifier="MYSQL".equals(source.type())?request.identity().catalog():request.identity().schema();List<String> statements=ObjectDialect.forProfile(source.profileId()).createStatements(new ObjectDialect.Table(qualifier,request.identity().table(),request.fields()));String sql=String.join(";\n",statements);var persisted=jdbc.queryForList("SELECT statement_text FROM data_object_operation_steps WHERE operation_id=? ORDER BY step_number",String.class,id);if(!statements.equals(persisted))throw new IllegalStateException("预览内容已变化，请重新预览");
        if(jdbc.update("UPDATE data_object_operations SET status='EXECUTING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PREPARED'",id)!=1)throw new IllegalStateException("操作已执行或正在执行");
        try {if(exists(request.identity())){finish(id,"FAILED","目标物理表已存在，请使用注册功能",true);throw new IllegalStateException("目标物理表已存在，请使用注册功能");}}
        catch(IllegalStateException e){throw e;}catch(Exception e){finish(id,"FAILED","执行前无法确认物理表状态",true);throw new IllegalStateException("执行前无法确认物理表状态");}
        try(var connection=connections.get(request.identity().sourceId()).getConnection();var statement=connection.createStatement()){statement.setQueryTimeout(30);for(int i=0;i<statements.size();i++){jdbc.update("UPDATE data_object_operation_steps SET status='EXECUTING' WHERE operation_id=? AND step_number=?",id,i+1);statement.execute(statements.get(i));jdbc.update("UPDATE data_object_operation_steps SET status='SUCCEEDED' WHERE operation_id=? AND step_number=?",id,i+1);}}
        catch(Exception e){finish(id,"UNKNOWN","数据库执行结果待核实，请检查目标表",false);return new Preview(id,request.identity(),sql,"UNKNOWN");}
        try{var fields=metadata.fields(request.identity());verifyFields(request.fields(),fields);tx.executeWithoutResult(s->{long objectId=store.register(request.directoryId(),request.identity(),request.name(),request.description(),fields);jdbc.update("UPDATE data_object_operation_steps SET status='SUCCEEDED' WHERE operation_id=?",id);jdbc.update("UPDATE data_object_operations SET object_id=?,status='SUCCEEDED',active_identity=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",objectId,id);});return new Preview(id,request.identity(),sql,"SUCCEEDED");}
        catch(Exception e){finish(id,"NEEDS_REGISTRATION","物理表可能已创建，请核对后重新登记",false);return new Preview(id,request.identity(),sql,"NEEDS_REGISTRATION");}
    }
    private static void verifyFields(List<ObjectDialect.Field> expected,List<ObjectStore.Field> actual) {
        if (expected.size()!=actual.size()) throw new IllegalStateException("实际字段与预期不一致，请人工核对");
        for (int i=0;i<expected.size();i++) {
            var e=expected.get(i); var a=actual.get(i); String type=e.type().toUpperCase(Locale.ROOT); String nativeType=a.type().toUpperCase(Locale.ROOT);
            boolean sameType=type.equals(nativeType) || type.equals("INT") && nativeType.equals("INTEGER") || type.equals("INTEGER") && nativeType.equals("INT");
            if (!e.name().equals(a.name()) || !sameType || !(e.primaryKey()?"YES":"NO").equals(a.primaryKey()) || !((e.nullable()&&!e.primaryKey())?"YES":"NO").equals(a.nullable())) throw new IllegalStateException("实际字段与预期不一致，请人工核对");
            if (Set.of("VARCHAR","VARCHAR2","CHAR","DECIMAL","NUMERIC","NUMBER").contains(type) && e.length()!=null && !e.length().equals(a.length())) throw new IllegalStateException("实际字段精度与预期不一致，请人工核对");
            if (e.remarks()!=null&&!e.remarks().isEmpty()&&!e.remarks().equals(a.remarks())) throw new IllegalStateException("实际字段注释与预期不一致，请人工核对");
            if (e.scale()!=null && !e.scale().equals(a.scale())) throw new IllegalStateException("实际字段小数位与预期不一致，请人工核对");
        }
    }
    private void finish(String id,String status,String message,boolean release){tx.executeWithoutResult(s->{jdbc.update("UPDATE data_object_operation_steps SET status=?,error_message=? WHERE operation_id=? AND status='EXECUTING'",status,message,id);jdbc.update("UPDATE data_object_operations SET status=?,error_message=?,active_identity="+(release?"NULL":"active_identity")+",updated_at=CURRENT_TIMESTAMP WHERE id=?",status,message,id);});}
    public void cancel(String id){if(jdbc.update("UPDATE data_object_operations SET status='CANCELLED',active_identity=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PREPARED'",id)!=1)throw new IllegalStateException("只有待执行的预览可以取消");}
    public List<PendingOperation> pending(){return jdbc.query("SELECT id,target_json,status,error_message,created_at FROM data_object_operations WHERE operation_type='CREATE' AND status IN ('PREPARED','EXECUTING','UNKNOWN','NEEDS_REGISTRATION') ORDER BY created_at DESC",(r,n)->{try{CreateRequest request=mapper.readValue(r.getString(2),CreateRequest.class);return new PendingOperation(r.getString(1),request.identity(),request.name(),r.getString(3),r.getString(4),r.getTimestamp(5).toLocalDateTime().toString());}catch(Exception e){throw new IllegalStateException("操作记录损坏");}});}

    public void resolveAbsent(String id) throws Exception {try(var lease=guard.acquire(id)){Pending p=get(id);if(!"CREATE".equals(p.type())||!Set.of("EXECUTING","UNKNOWN","NEEDS_REGISTRATION").contains(p.status()))throw new IllegalStateException("当前操作不能核对");CreateRequest request=mapper.readValue(p.target(),CreateRequest.class);metadata.validateTarget(request.identity());if(exists(request.identity()))throw new IllegalStateException("目标表已存在，请使用核对并重新登记");if(jdbc.update("UPDATE data_object_operations SET status='FAILED',active_identity=NULL,error_message='经核对目标表不存在',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('EXECUTING','UNKNOWN','NEEDS_REGISTRATION')",id)!=1)throw new IllegalStateException("操作状态已变化");}}
    public Preview recoverCreate(String id) throws Exception {try(var lease=guard.acquire(id)){return recoverCreateLocked(id);}}
    private Preview recoverCreateLocked(String id) throws Exception {
        Pending p=get(id);if(!"CREATE".equals(p.type())||!Set.of("UNKNOWN","NEEDS_REGISTRATION","EXECUTING").contains(p.status()))throw new IllegalStateException("当前状态不能重新登记");CreateRequest request=mapper.readValue(p.target(),CreateRequest.class);metadata.validateTarget(request.identity());var fields=metadata.fields(request.identity());
        verifyFields(request.fields(),fields);
        Long objectId=tx.execute(status->{long registered=store.register(request.directoryId(),request.identity(),request.name(),request.description(),fields);if(jdbc.update("UPDATE data_object_operations SET object_id=?,status='SUCCEEDED',active_identity=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('UNKNOWN','NEEDS_REGISTRATION','EXECUTING')",registered,id)!=1)throw new IllegalStateException("操作状态已变化");jdbc.update("UPDATE data_object_operation_steps SET status='SUCCEEDED',error_message=NULL WHERE operation_id=?",id);return registered;});if(objectId==null)throw new IllegalStateException("重新登记失败");return new Preview(id,request.identity(),p.sql(),"SUCCEEDED");
    }
}
