package com.example.governance.dataobject;

import com.example.governance.assets.ExecutionDataSourceProvider;
import com.example.governance.query.DatasetPolicyStore;
import com.example.governance.query.DatasetQueryEngine;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Delegate row and field filtering to the existing dataset policy engine. */
@Service
public class ObjectPreviewService {
    private final ObjectStore objects;private final DatasetPolicyStore policies;private final DatasetQueryEngine engine;private final ExecutionDataSourceProvider sources;private final com.example.governance.assets.DataSourceStore definitions;
    public ObjectPreviewService(ObjectStore objects,DatasetPolicyStore policies,DatasetQueryEngine engine,ExecutionDataSourceProvider sources,com.example.governance.assets.DataSourceStore definitions){this.objects=objects;this.policies=policies;this.engine=engine;this.sources=sources;this.definitions=definitions;}
    public List<Map<String,Object>> preview(long userId,long objectId,List<String> fields,int limit) throws Exception {
        var object=objects.find(objectId);if(object.datasetId()==null)throw new SecurityException("对象未关联有访问策略的数据集");
        var resolved=policies.resolve(userId,object.datasetId());var identity=object.identity();var dataset=resolved.dataset();
        if(resolved.sourceId()!=identity.sourceId()||!java.util.Objects.equals(dataset.table(),identity.table())||!java.util.Objects.equals(normal(dataset.catalog()),normal(identity.catalog()))||!sameSchema(dataset.schema(),identity))throw new SecurityException("数据集与对象不匹配");
        var selected=(fields==null||fields.isEmpty())?resolved.policy().fields().keySet().stream().filter(resolved.dataset().fields()::contains).sorted().limit(100).toList():fields;
        if(selected.isEmpty())throw new SecurityException("对象没有可访问字段");
        return engine.query(sources.get(resolved.sourceId()),resolved.dataset(),resolved.policy(),selected,limit);
    }
    private boolean sameSchema(String schema,ObjectStore.Identity identity){String a=normal(schema),b=normal(identity.schema());if(a.equals(b))return true;var definition=definitions.find(identity.sourceId());return definition.isPresent()&&"MYSQL".equals(definition.get().type())&&(a.isEmpty()||a.equals(normal(identity.catalog())))&&(b.isEmpty()||b.equals(normal(identity.catalog())));}
    private static String normal(String value){return value==null?"":value;}
}
