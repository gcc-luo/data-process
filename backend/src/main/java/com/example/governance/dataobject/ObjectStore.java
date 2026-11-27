package com.example.governance.dataobject;

import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectStore {
    public record Directory(long id, Long parentId, String name, int sortOrder) {}
    public record Identity(long sourceId, String catalog, String schema, String table, String type) {}
    public record Field(int ordinal, String name, String type, Integer length, Integer scale, String nullable, String primaryKey, String remarks) {}
    public record Item(long id, long directoryId, Identity identity, String name, String description, Long datasetId, long version, String updatedAt) {}
    public record Page(List<Item> rows, long total) {}
    private final JdbcTemplate jdbc;
    public ObjectStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Directory> directories() { return jdbc.query("SELECT id,parent_id,name,sort_order FROM object_directories ORDER BY sort_order,id", (r,n) -> new Directory(r.getLong(1),r.getObject(2,Long.class),r.getString(3),r.getInt(4))); }
    @Transactional public void sortDirectories(List<com.example.governance.common.DirectorySorter.Item> items){lock();com.example.governance.common.DirectorySorter.sort(jdbc,"object_directories",items);}
    private void lock() { jdbc.queryForList("SELECT setting_key FROM platform_settings WHERE setting_key='internal.organization.lock' FOR UPDATE"); }
    private static String clean(String name) { String value=name==null?"":name.trim(); if(value.isEmpty()||value.length()>120||value.contains("/"))throw new IllegalArgumentException("目录名称无效");return value; }
    private void unique(String name,Long parent,Long except) { if(directories().stream().anyMatch(d->Objects.equals(d.parentId(),parent)&&d.name().equals(name)&&!Objects.equals(d.id(),except)))throw new IllegalArgumentException("同级目录名称已存在"); }
    @Transactional public long createDirectory(String name, Long parent) {
        String value=clean(name);lock();if(parent!=null&&directories().stream().noneMatch(d->d.id()==parent))throw new IllegalArgumentException("父目录不存在");unique(value,parent,null);
        var key=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement("INSERT INTO object_directories(parent_id,name) VALUES (?,?)",new String[]{"id"});if(parent==null)s.setNull(1,Types.BIGINT);else s.setLong(1,parent);s.setString(2,value);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();
    }
    @Transactional public void renameDirectory(long id,String name) { String value=clean(name);lock();var node=directories().stream().filter(d->d.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("目录不存在"));unique(value,node.parentId(),id);jdbc.update("UPDATE object_directories SET name=? WHERE id=?",value,id); }
    @Transactional public void deleteDirectory(long id) { lock();if(jdbc.queryForList("SELECT id FROM object_directories WHERE id=? FOR UPDATE",Long.class,id).isEmpty())throw new IllegalArgumentException("目录不存在");if(jdbc.queryForObject("SELECT COUNT(*) FROM object_directories WHERE parent_id=?",Integer.class,id)>0||jdbc.queryForObject("SELECT COUNT(*) FROM data_objects WHERE directory_id=?",Integer.class,id)>0)throw new IllegalStateException("目录包含子目录或数据对象，不能删除");jdbc.update("DELETE FROM object_directories WHERE id=?",id); }
    public static String identityHash(Identity identity) {
        if(identity==null||identity.sourceId()<1||identity.table()==null||identity.table().isBlank()||identity.table().length()>255||!Set.of("TABLE","VIEW").contains(identity.type()))throw new IllegalArgumentException("物理对象身份无效");
        try { var out=new java.io.ByteArrayOutputStream();var data=new java.io.DataOutputStream(out);data.writeLong(identity.sourceId());for(String value:List.of(normal(identity.catalog()),normal(identity.schema()),identity.table(),identity.type())){byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);data.writeInt(bytes.length);data.write(bytes);}return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(out.toByteArray())); }
        catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String normal(String value){if(value==null)return "";if(value.length()>255)throw new IllegalArgumentException("数据库或模式名称过长");return value;}
    @Transactional public long register(long directoryId,Identity identity,String name,String description,List<Field> fields) {
        String hash=identityHash(identity);String label=name==null?"":name.trim();String detail=description==null?"":description.trim();if(label.isEmpty()||label.length()>200||detail.length()>1000||fields==null||fields.isEmpty())throw new IllegalArgumentException("对象信息或字段无效");
        if(jdbc.queryForList("SELECT id FROM object_directories WHERE id=? FOR UPDATE",Long.class,directoryId).isEmpty())throw new IllegalArgumentException("目录不存在");
        if(jdbc.queryForList("SELECT id FROM data_sources WHERE id=? FOR UPDATE",Long.class,identity.sourceId()).isEmpty())throw new IllegalArgumentException("数据源不存在");
        Set<Integer> positions=new HashSet<>();for(Field f:fields)if(f==null||f.ordinal()<1||!positions.add(f.ordinal())||f.name()==null||f.name().isBlank()||f.name().length()>255||f.type()==null||f.type().isBlank()||f.type().length()>255||!Set.of("YES","NO","UNKNOWN").contains(f.nullable())||!Set.of("YES","NO","UNKNOWN").contains(f.primaryKey())||f.remarks()!=null&&f.remarks().length()>1000)throw new IllegalArgumentException("字段元数据无效");
        var key=new GeneratedKeyHolder();try {jdbc.update(c->{var s=c.prepareStatement("INSERT INTO data_objects(directory_id,source_id,catalog_name,schema_name,table_name,object_type,identity_hash,display_name,description) VALUES (?,?,?,?,?,?,?,?,?)",new String[]{"id"});s.setLong(1,directoryId);s.setLong(2,identity.sourceId());s.setString(3,normal(identity.catalog()));s.setString(4,normal(identity.schema()));s.setString(5,identity.table());s.setString(6,identity.type());s.setString(7,hash);s.setString(8,label);s.setString(9,detail);return s;},key);}catch(org.springframework.dao.DuplicateKeyException ex){throw new IllegalStateException("该物理对象已登记",ex);}long id=Objects.requireNonNull(key.getKey()).longValue();
        for(Field f:fields)jdbc.update("INSERT INTO data_object_fields(object_id,ordinal_position,field_name,native_type,field_length,field_scale,nullable_state,primary_key_state,remarks) VALUES (?,?,?,?,?,?,?,?,?)",id,f.ordinal(),f.name(),f.type(),f.length(),f.scale(),f.nullable(),f.primaryKey(),f.remarks());return id;
    }
    public Page page(Long directoryId,String table,String name,int page,int size) {
        if(page<1||page>100000||size<1||size>100)throw new IllegalArgumentException("分页参数无效");List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(directoryId!=null){Set<Long> ids=new HashSet<>();ids.add(directoryId);List<Directory> all=directories();if(all.stream().noneMatch(d->d.id()==directoryId))throw new IllegalArgumentException("目录不存在");boolean changed=true;while(changed){changed=false;for(var d:all)if(d.parentId()!=null&&ids.contains(d.parentId())&&ids.add(d.id()))changed=true;}where.append(" AND directory_id IN (").append(String.join(",",Collections.nCopies(ids.size(),"?"))).append(")");args.addAll(ids);}
        for(var value:List.of(table==null?"":table.trim(),name==null?"":name.trim()))if(value.length()>200)throw new IllegalArgumentException("查询条件过长");
        if(table!=null&&!table.isBlank()){where.append(" AND LOWER(table_name) LIKE ? ESCAPE '!'");args.add(pattern(table));}if(name!=null&&!name.isBlank()){where.append(" AND LOWER(display_name) LIKE ? ESCAPE '!'");args.add(pattern(name));}
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM data_objects"+where,Long.class,args.toArray()));List<Object> ps=new ArrayList<>(args);ps.add(size);ps.add((page-1)*size);
        var rows=jdbc.query("SELECT id,directory_id,source_id,catalog_name,schema_name,table_name,object_type,display_name,description,dataset_id,version,updated_at FROM data_objects"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",(r,n)->new Item(r.getLong(1),r.getLong(2),new Identity(r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),r.getString(8),r.getString(9),r.getObject(10,Long.class),r.getLong(11),r.getString(12)),ps.toArray());return new Page(rows,total);
    }
    public List<Item> all() {
        return jdbc.query("SELECT id,directory_id,source_id,catalog_name,schema_name,table_name,object_type,display_name,description,dataset_id,version,updated_at FROM data_objects ORDER BY directory_id,id",
                (r,n)->new Item(r.getLong(1),r.getLong(2),new Identity(r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),r.getString(8),r.getString(9),r.getObject(10,Long.class),r.getLong(11),r.getString(12)));
    }
    private static String pattern(String value){return "%"+value.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    public Item find(long id) {
        return jdbc.query("SELECT id,directory_id,source_id,catalog_name,schema_name,table_name,object_type,display_name,description,dataset_id,version,updated_at FROM data_objects WHERE id=?",(r,n)->new Item(r.getLong(1),r.getLong(2),new Identity(r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),r.getString(8),r.getString(9),r.getObject(10,Long.class),r.getLong(11),r.getString(12)),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("数据对象不存在"));
    }
    public List<Field> fields(long id) { find(id);return jdbc.query("SELECT ordinal_position,field_name,native_type,field_length,field_scale,nullable_state,primary_key_state,remarks FROM data_object_fields WHERE object_id=? ORDER BY ordinal_position",(r,n)->new Field(r.getInt(1),r.getString(2),r.getString(3),r.getObject(4,Integer.class),r.getObject(5,Integer.class),r.getString(6),r.getString(7),r.getString(8)),id); }
    @Transactional public void update(long id,long directoryId,String name,String description,long version){
        String label=name==null?"":name.trim(), detail=description==null?"":description.trim();if(label.isEmpty()||label.length()>200||detail.length()>1000)throw new IllegalArgumentException("对象信息无效");
        if(jdbc.queryForList("SELECT id FROM object_directories WHERE id=? FOR UPDATE",Long.class,directoryId).isEmpty())throw new IllegalArgumentException("目录不存在");
        if(jdbc.update("UPDATE data_objects SET directory_id=?,display_name=?,description=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?",directoryId,label,detail,id,version)!=1)throw new IllegalStateException("对象不存在或已被修改，请刷新后重试");
    }
    @Transactional public void delete(long id){
        var rows=jdbc.queryForList("SELECT dataset_id FROM data_objects WHERE id=? FOR UPDATE",Long.class,id);if(rows.isEmpty())throw new IllegalArgumentException("数据对象不存在");
        if(rows.get(0)!=null)throw new IllegalStateException("对象已关联数据集，请先解除关联");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM data_object_operations WHERE object_id=? AND active_identity IS NOT NULL",Integer.class,id)>0)throw new IllegalStateException("对象存在待处理的结构操作，不能删除");
        jdbc.update("UPDATE data_object_operations SET object_id=NULL WHERE object_id=?",id);
        jdbc.update("DELETE FROM data_object_drafts WHERE object_id=?",id);jdbc.update("DELETE FROM data_object_fields WHERE object_id=?",id);jdbc.update("DELETE FROM data_objects WHERE id=?",id);
    }
    private boolean sameSchema(Identity candidate,Identity actual){String a=normal(candidate.schema()),b=normal(actual.schema());if(Objects.equals(a,b))return true;var type=jdbc.queryForList("SELECT source_type FROM data_sources WHERE id=?",String.class,actual.sourceId());return !type.isEmpty()&&"MYSQL".equals(type.get(0))&&(a.isEmpty()||a.equals(normal(actual.catalog())))&&(b.isEmpty()||b.equals(normal(actual.catalog())));}
    @Transactional public void bindDataset(long id,Long datasetId){
        if(jdbc.queryForList("SELECT id FROM data_objects WHERE id=? FOR UPDATE",Long.class,id).isEmpty())throw new IllegalArgumentException("数据对象不存在");
        Item object=find(id);if(datasetId!=null){var rows=jdbc.query("SELECT source_id,catalog_name,schema_name,table_name FROM datasets WHERE id=?",(r,n)->new Identity(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),object.identity().type()),datasetId);if(rows.isEmpty())throw new IllegalArgumentException("数据集不存在");var candidate=rows.get(0);var actual=object.identity();if(candidate.sourceId()!=actual.sourceId()||!Objects.equals(candidate.table(),actual.table())||!Objects.equals(normal(candidate.catalog()),normal(actual.catalog()))||!sameSchema(candidate,actual))throw new IllegalArgumentException("数据集必须关联相同的物理对象");}
        jdbc.update("UPDATE data_objects SET dataset_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",datasetId,id);
    }
}
