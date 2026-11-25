package com.example.governance.dataobject;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObjectDialectTest {
    @Test void rendersCreateForAllConfiguredProfiles() {
        for (String profile : List.of("MYSQL:5.x", "MYSQL:8.x", "POSTGRESQL:general", "ORACLE:11g", "ORACLE:12c+")) {
            ObjectDialect d = ObjectDialect.forProfile(profile);
            String type = profile.startsWith("ORACLE") ? "VARCHAR2" : "VARCHAR";
            String sql = d.createTable(new ObjectDialect.Table("public_schema", "customer", List.of(new ObjectDialect.Field("code", type, 20, null, false, true))));
            assertTrue(sql.startsWith("CREATE TABLE "));
            assertTrue(sql.contains("PRIMARY KEY"));
            assertTrue(sql.contains(type + "(20)"));
        }
    }
    @Test void rejectsUnsafeIdentifiersAndUnrecognizedTypes() {
        ObjectDialect d = ObjectDialect.forProfile("MYSQL:8.x");
        assertThrows(IllegalArgumentException.class, () -> d.quoteIdentifier("x; DROP TABLE t"));
        assertThrows(IllegalArgumentException.class, () -> d.createTable(new ObjectDialect.Table(null, "t", List.of(new ObjectDialect.Field("c", "INT); DROP TABLE t", null, null, true, false)))));
        assertThrows(IllegalArgumentException.class, () -> ObjectDialect.forProfile("OTHER"));
    }
    @Test void generatesSafeColumnCommentsForEveryDatabase() {
        for(String profile:List.of("MYSQL:5.x","MYSQL:8.x","POSTGRESQL:general","ORACLE:11g","ORACLE:12c+")) {
            var dialect=ObjectDialect.forProfile(profile);String type=profile.startsWith("ORACLE")?"VARCHAR2":"VARCHAR";
            var table=new ObjectDialect.Table("DATA", "T", List.of(new ObjectDialect.Field("CODE",type,20,null,true,false,"客户'编号")));
            var statements=dialect.createStatements(table);assertEquals(profile.startsWith("MYSQL")?1:2,statements.size());assertTrue(String.join(" ",statements).contains("客户''编号"));
            assertThrows(IllegalArgumentException.class,()->dialect.createStatements(new ObjectDialect.Table("DATA","T",List.of(new ObjectDialect.Field("CODE",type,20,null,true,false,"x\\'y")))));
        }
    }
    @Test void newDictionaryFieldPublishesCommentForEveryProfile() {
        for(String profile:List.of("MYSQL:5.x","MYSQL:8.x","POSTGRESQL:general","ORACLE:11g","ORACLE:12c+")) {
            var dialect=ObjectDialect.forProfile(profile);String type=profile.startsWith("ORACLE")?"VARCHAR2":"VARCHAR";
            var old=new ObjectDialect.Field("ID",type,20,null,true,false);var added=new ObjectDialect.Field("CUSTOMER",type,30,null,true,false,"客户名称");
            var changes=dialect.planChanges(new ObjectDialect.Table("DATA","T",List.of(old)),new ObjectDialect.Table("DATA","T",List.of(old,added)));
            assertEquals(profile.startsWith("MYSQL")?1:2,changes.size());assertTrue(changes.stream().anyMatch(c->c.sql().contains("客户名称")));
        }
    }
}
