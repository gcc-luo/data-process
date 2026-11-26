package com.example.governance.dataobject;

import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.ExecutionDataSourceProvider;
import java.security.GeneralSecurityException;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** JDBC metadata discovery with exact identity checks and explicit unknown values. */
@Service
public class ObjectMetadata {
    private static final int MAX_TABLES=1000, MAX_FIELDS=1000;
    private final DataSourceStore sources;
    private final ExecutionDataSourceProvider connections;
    public ObjectMetadata(DataSourceStore sources, ExecutionDataSourceProvider connections) { this.sources=sources; this.connections=connections; }
    public record Table(String catalog,String schema,String name,String type) {}
    public record Column(int ordinal,String name,String type,int jdbcType,Integer length,Integer scale,String nullable,String primaryKey,String autoIncrement,String generated,String defaultValue,String remarks) {}
    private record Scope(String catalog,String schema) {}
    private static String normalized(String s){return s==null||s.isBlank()?null:s;}
    private Scope scope(Connection c,long sourceId,String catalog,String schema) throws SQLException {
        var source=sources.find(sourceId).orElseThrow(()->new IllegalArgumentException("数据源不存在"));
        catalog=normalized(catalog);schema=normalized(schema);
        if(!"MYSQL".equals(source.type()))return new Scope(catalog,schema);
        String current=normalized(c.getCatalog());if(current==null)current=source.database();
        if(catalog!=null&&!catalog.equals(current)||schema!=null&&!schema.equals(current))throw new IllegalArgumentException("目标数据库超出数据源范围");
        return new Scope(current,null);
    }
    private static String pattern(String value,String escape) throws SQLException { if(value==null)return null;if(escape==null||escape.isEmpty()){if(value.contains("_")||value.contains("%"))throw new SQLException("Metadata pattern escaping unavailable");return value;}return value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%"); }
    private static boolean same(String requested,String actual){return requested==null||Objects.equals(requested,actual);}
    public void validateTarget(ObjectStore.Identity identity) throws SQLException,GeneralSecurityException {
        ObjectStore.identityHash(identity);var source=sources.find(identity.sourceId()).orElseThrow(()->new IllegalArgumentException("数据源不存在"));
        try(var c=connections.get(identity.sourceId()).getConnection()){scope(c,identity.sourceId(),identity.catalog(),identity.schema());
            if(!"MYSQL".equals(source.type()) && (identity.schema()==null||identity.schema().isBlank()))throw new IllegalArgumentException("请选择目标模式");
            if(!"MYSQL".equals(source.type()) && !Objects.equals(normalized(identity.catalog()),normalized(c.getCatalog())))throw new IllegalArgumentException("目标数据库与当前连接不一致");
        }
    }
    public List<Table> tables(long sourceId,String catalog,String schema) throws SQLException,GeneralSecurityException {
        try(var connection=connections.get(sourceId).getConnection()) {var md=connection.getMetaData();var scope=scope(connection,sourceId,catalog,schema);var result=new ArrayList<Table>();
            try(var rows=md.getTables(scope.catalog(),pattern(scope.schema(),md.getSearchStringEscape()),"%",new String[]{"TABLE","VIEW"})){while(rows.next()){if(result.size()>=MAX_TABLES)throw new SQLException("表数量超出限制，请缩小查询范围");String actualCatalog=rows.getString("TABLE_CAT"), actualSchema=rows.getString("TABLE_SCHEM"), name=rows.getString("TABLE_NAME"), type=rows.getString("TABLE_TYPE");if("BASE TABLE".equals(type))type="TABLE";if(!same(scope.catalog(),actualCatalog)||!same(scope.schema(),actualSchema)||name==null||!Set.of("TABLE","VIEW").contains(type))throw new SQLException("返回的表身份与查询条件不一致");result.add(new Table(actualCatalog,actualSchema,name,type));}}
            return List.copyOf(result);
        }
    }
    public List<Column> columns(ObjectStore.Identity identity) throws SQLException,GeneralSecurityException {
        ObjectStore.identityHash(identity);
        try(var connection=connections.get(identity.sourceId()).getConnection()){var md=connection.getMetaData();var scope=scope(connection,identity.sourceId(),identity.catalog(),identity.schema());String escape=md.getSearchStringEscape();
            List<Table> found=tables(identity.sourceId(),identity.catalog(),identity.schema()).stream().filter(t->Objects.equals(t.name(),identity.table())&&Objects.equals(t.type(),identity.type())&&Objects.equals(normalized(t.catalog()),normalized(identity.catalog()))&&Objects.equals(normalized(t.schema()),normalized(identity.schema()))).toList();if(found.size()!=1)throw new IllegalArgumentException("物理对象不存在或身份不明确");
            Set<String> keys=new HashSet<>();boolean keysKnown=true;try(var rs=md.getPrimaryKeys(scope.catalog(),scope.schema(),identity.table())){while(rs.next()){if(!Objects.equals(rs.getString("TABLE_NAME"),identity.table())||!same(scope.catalog(),rs.getString("TABLE_CAT"))||!same(scope.schema(),rs.getString("TABLE_SCHEM")))throw new SQLException("主键元数据身份不一致");keys.add(rs.getString("COLUMN_NAME"));}}catch(SQLFeatureNotSupportedException e){keysKnown=false;}
            var result=new ArrayList<Column>();try(var rs=md.getColumns(scope.catalog(),pattern(scope.schema(),escape),pattern(identity.table(),escape),"%")){var labels=columnLabels(rs.getMetaData());while(rs.next()){if(result.size()>=MAX_FIELDS)throw new SQLException("字段数量超出限制");if(!Objects.equals(rs.getString("TABLE_NAME"),identity.table())||!same(scope.catalog(),rs.getString("TABLE_CAT"))||!same(scope.schema(),rs.getString("TABLE_SCHEM")))throw new SQLException("字段元数据身份不一致");String name=rs.getString("COLUMN_NAME"), type=rs.getString("TYPE_NAME");if(name==null||type==null)throw new SQLException("字段元数据不完整");int size=rs.getInt("COLUMN_SIZE");Integer length=rs.wasNull()?null:size;int decimals=rs.getInt("DECIMAL_DIGITS");Integer scale=rs.wasNull()?null:decimals;int nullable=rs.getInt("NULLABLE");String nullState=rs.wasNull()?"UNKNOWN":nullable==DatabaseMetaData.columnNoNulls?"NO":nullable==DatabaseMetaData.columnNullable?"YES":"UNKNOWN";int ordinal=rs.getInt("ORDINAL_POSITION");if(rs.wasNull()||ordinal<1)throw new SQLException("字段顺序未知");int jdbcType=rs.getInt("DATA_TYPE");if(rs.wasNull())throw new SQLException("字段类型未知");result.add(new Column(ordinal,name,type,jdbcType,length,scale,nullState,keysKnown?(keys.contains(name)?"YES":"NO"):"UNKNOWN",metadataFlag(rs,labels,"IS_AUTOINCREMENT"),metadataFlag(rs,labels,"IS_GENERATEDCOLUMN"),metadataValue(rs,labels,"COLUMN_DEF"),metadataValue(rs,labels,"REMARKS")));}}
            if(result.isEmpty())throw new IllegalArgumentException("未能读取字段信息");return List.copyOf(result);
        }
    }
    public List<ObjectStore.Field> fields(ObjectStore.Identity identity) throws SQLException,GeneralSecurityException {
        return columns(identity).stream().map(c->new ObjectStore.Field(c.ordinal(),c.name(),c.type(),c.length(),c.scale(),c.nullable(),c.primaryKey(),c.remarks())).toList();
    }
    private static Set<String> columnLabels(ResultSetMetaData metadata) throws SQLException {
        Set<String> names=new HashSet<>();for(int i=1;i<=metadata.getColumnCount();i++){names.add(metadata.getColumnLabel(i).toUpperCase(Locale.ROOT));names.add(metadata.getColumnName(i).toUpperCase(Locale.ROOT));}return names;
    }
    private static String metadataFlag(ResultSet rows,Set<String> labels,String column) throws SQLException {
        if(!labels.contains(column))return "UNKNOWN";String value=rows.getString(column);return value!=null&&Set.of("YES","NO").contains(value.toUpperCase(Locale.ROOT))?value.toUpperCase(Locale.ROOT):"UNKNOWN";
    }
    private static String metadataValue(ResultSet rows,Set<String> labels,String column) throws SQLException {
        return labels.contains(column)?rows.getString(column):null;
    }
}
