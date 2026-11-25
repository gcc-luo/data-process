package com.example.governance.dataobject;

import java.util.List;
import java.util.Set;

/** Generates DDL from validated structural models; never accepts executable SQL from callers. */
public interface ObjectDialect {
    record Field(String name, String type, Integer length, Integer scale, boolean nullable, boolean primaryKey, String remarks) {
        public Field(String name,String type,Integer length,Integer scale,boolean nullable,boolean primaryKey){this(name,type,length,scale,nullable,primaryKey,null);}
    }
    record Table(String schema, String name, List<Field> fields) {}
    record Change(String sql, String risk) {}
    Set<String> supportedTypes();
    String quoteIdentifier(String identifier);
    String createTable(Table table);
    List<String> createStatements(Table table);
    List<Change> planChanges(Table before,Table after);
    static ObjectDialect forProfile(String profile) {
        return switch (profile) {
            case "MYSQL:5.x", "MYSQL:8.x" -> new RelationalObjectDialect(RelationalObjectDialect.Engine.MYSQL);
            case "POSTGRESQL:general" -> new RelationalObjectDialect(RelationalObjectDialect.Engine.POSTGRESQL);
            case "ORACLE:11g", "ORACLE:12c+" -> new RelationalObjectDialect(RelationalObjectDialect.Engine.ORACLE);
            default -> throw new IllegalArgumentException("不支持的数据源版本");
        };
    }
}
