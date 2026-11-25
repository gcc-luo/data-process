package com.example.governance.dataobject;

import java.util.*;
import java.util.stream.Collectors;

/** Conservative cross-database type subset; rejects unrecognized identifiers and types. */
final class RelationalObjectDialect implements ObjectDialect {
    enum Engine { MYSQL, POSTGRESQL, ORACLE }
    private final Engine engine;
    RelationalObjectDialect(Engine engine) { this.engine = engine; }
    @Override public Set<String> supportedTypes() { return switch (engine) {
        case MYSQL -> Set.of("VARCHAR", "CHAR", "INT", "BIGINT", "DECIMAL", "DATE", "DATETIME");
        case POSTGRESQL -> Set.of("VARCHAR", "CHAR", "INTEGER", "BIGINT", "NUMERIC", "DATE", "TIMESTAMP");
        case ORACLE -> Set.of("VARCHAR2", "CHAR", "NUMBER", "DATE", "TIMESTAMP");
    }; }
    @Override public String quoteIdentifier(String value) {
        int max = engine == Engine.ORACLE ? 30 : engine == Engine.POSTGRESQL ? 63 : 64;
        if (value == null || value.length() > max || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("数据库标识符无效");
        String mark = engine == Engine.MYSQL ? "`" : "\"";
        return mark + value + mark;
    }
    private String columnType(Field f) {
        String type = f.type() == null ? "" : f.type().toUpperCase(Locale.ROOT);
        if (!supportedTypes().contains(type)) throw new IllegalArgumentException("该数据源不支持此字段类型");
        boolean character = Set.of("VARCHAR", "VARCHAR2", "CHAR").contains(type);
        boolean numeric = Set.of("DECIMAL", "NUMERIC", "NUMBER").contains(type);
        Integer length = f.length(), scale = f.scale();
        if (character) {
            int limit = engine == Engine.ORACLE ? (type.equals("CHAR") ? 2000 : 4000) : engine == Engine.MYSQL && type.equals("CHAR") ? 255 : 65535;
            if (length == null || length < 1 || length > limit || scale != null) throw new IllegalArgumentException("字符长度无效");
            return type + "(" + length + ")";
        }
        if (numeric && length != null) {
            int limit = engine == Engine.ORACLE ? 38 : engine == Engine.MYSQL ? 65 : 1000;
            int scaleLimit = engine == Engine.MYSQL ? 30 : limit;
            if (length < 1 || length > limit || scale != null && (scale < 0 || scale > length || scale > scaleLimit)) throw new IllegalArgumentException("数值精度无效");
            return type + "(" + length + (scale == null ? "" : "," + scale) + ")";
        }
        if (length != null || scale != null) throw new IllegalArgumentException("该字段类型不接受长度或精度");
        return type;
    }
    @Override public String createTable(Table table) {
        if (table == null || table.fields() == null || table.fields().isEmpty() || table.fields().size() > 1000) throw new IllegalArgumentException("字段数量无效");
        String name = (table.schema() == null || table.schema().isBlank() ? "" : quoteIdentifier(table.schema()) + ".") + quoteIdentifier(table.name());
        Set<String> names = new HashSet<>();
        List<String> definitions = new ArrayList<>(), keys = new ArrayList<>();
        for (Field f : table.fields()) {
            if (f == null || !names.add(f.name() == null ? "" : f.name().toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("字段重复或无效");
            String identifier = quoteIdentifier(f.name());
            String comment=commentLiteral(f.remarks());
            definitions.add(identifier + " " + columnType(f) + (f.nullable() && !f.primaryKey() ? "" : " NOT NULL") + (engine==Engine.MYSQL && comment!=null ? " COMMENT "+comment : ""));
            if (f.primaryKey()) keys.add(identifier);
        }
        if (!keys.isEmpty()) definitions.add("PRIMARY KEY (" + String.join(", ", keys) + ")");
        return "CREATE TABLE " + name + " (" + String.join(", ", definitions) + ")";
    }
    private String commentLiteral(String value) {
        if(value==null||value.isEmpty())return null;
        if(value.length()>1000||value.indexOf('\\')>=0||value.chars().anyMatch(c->c<32))throw new IllegalArgumentException("字段注释不能超过 1000 字，且不能包含反斜线或控制字符");
        return "'"+value.replace("'","''")+"'";
    }
    @Override public List<String> createStatements(Table table) {
        List<String> result=new ArrayList<>();result.add(createTable(table));
        if(engine!=Engine.MYSQL){String name=(table.schema()==null||table.schema().isBlank()?"":quoteIdentifier(table.schema())+".")+quoteIdentifier(table.name());for(Field f:table.fields()){String comment=commentLiteral(f.remarks());if(comment!=null)result.add("COMMENT ON COLUMN "+name+"."+quoteIdentifier(f.name())+" IS "+comment);}}
        return List.copyOf(result);
    }
    @Override public List<Change> planChanges(Table before,Table after) {
        if(before==null||after==null||!Objects.equals(before.schema(),after.schema())||!Objects.equals(before.name(),after.name())||before.fields()==null||after.fields()==null||after.fields().isEmpty())throw new IllegalArgumentException("目标表身份或字段无效");
        String table=(after.schema()==null||after.schema().isBlank()?"":quoteIdentifier(after.schema())+".")+quoteIdentifier(after.name());
        Map<String,Field> old=new LinkedHashMap<>(), next=new LinkedHashMap<>();
        for(Field f:before.fields()){if(f==null||old.putIfAbsent(f.name(),f)!=null)throw new IllegalArgumentException("原字段无效");quoteIdentifier(f.name());}
        for(Field f:after.fields()){if(f==null||next.putIfAbsent(f.name(),f)!=null)throw new IllegalArgumentException("目标字段无效");quoteIdentifier(f.name());}
        Set<String> oldKeys=old.values().stream().filter(Field::primaryKey).map(Field::name).collect(Collectors.toSet()),newKeys=next.values().stream().filter(Field::primaryKey).map(Field::name).collect(Collectors.toSet());if(!oldKeys.equals(newKeys))throw new IllegalArgumentException("不支持修改主键");
        if(old.keySet().stream().anyMatch(k->!next.containsKey(k)) && next.keySet().stream().anyMatch(k->!old.containsKey(k)))throw new IllegalArgumentException("不支持字段重命名；新增和删除请分开发布");
        List<Change> changes=new ArrayList<>();
        for(Field field:next.values()){
            Field previous=old.get(field.name());if(previous==null){String type=columnType(field),comment=commentLiteral(field.remarks());changes.add(new Change("ALTER TABLE "+table+(engine==Engine.ORACLE?" ADD (":" ADD COLUMN ")+quoteIdentifier(field.name())+" "+type+(field.nullable()?"":" NOT NULL")+(engine==Engine.MYSQL&&comment!=null?" COMMENT "+comment:"")+(engine==Engine.ORACLE?")":""),field.nullable()?"":"新增非空字段可能失败"));if(engine!=Engine.MYSQL&&comment!=null)changes.add(new Change("COMMENT ON COLUMN "+table+"."+quoteIdentifier(field.name())+" IS "+comment,""));continue;}
            boolean typeChanged=!Objects.equals(previous.type(),field.type())||!Objects.equals(previous.length(),field.length())||!Objects.equals(previous.scale(),field.scale());boolean nullChanged=previous.nullable()!=field.nullable();
            if(!typeChanged&&!nullChanged)continue;String type=columnType(field);columnType(previous);String risk=typeChanged?"类型或长度变更可能导致数据丢失":!field.nullable()?"设置非空可能失败":"";
            if(engine==Engine.MYSQL)changes.add(new Change("ALTER TABLE "+table+" MODIFY COLUMN "+quoteIdentifier(field.name())+" "+type+(field.nullable()?" NULL":" NOT NULL"),risk));
            else if(engine==Engine.ORACLE)changes.add(new Change("ALTER TABLE "+table+" MODIFY ("+quoteIdentifier(field.name())+" "+type+(nullChanged?(field.nullable()?" NULL":" NOT NULL"):"")+")",risk));
            else {if(typeChanged)changes.add(new Change("ALTER TABLE "+table+" ALTER COLUMN "+quoteIdentifier(field.name())+" TYPE "+type,risk));if(nullChanged)changes.add(new Change("ALTER TABLE "+table+" ALTER COLUMN "+quoteIdentifier(field.name())+(field.nullable()?" DROP NOT NULL":" SET NOT NULL"),field.nullable()?"":"设置非空可能失败"));}
        }
        for(Field f:old.values())if(!next.containsKey(f.name())){columnType(f);changes.add(new Change("ALTER TABLE "+table+" DROP COLUMN "+quoteIdentifier(f.name()),"删除字段将丢失该字段数据"));}
        if(changes.isEmpty())throw new IllegalArgumentException("没有可发布的字段变更");return List.copyOf(changes);
    }
}
