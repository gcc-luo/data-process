package com.example.governance.dataobject;

import com.example.governance.assets.DataSourceDefinition;
import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.ExecutionDataSourceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObjectMetadataTest {
    @Test void distinguishesSchemasAndReadsPrimaryKeys() throws Exception {
        var db=new DriverManagerDataSource("jdbc:h2:mem:object_metadata;DB_CLOSE_DELAY=-1","sa","");
        try(var c=db.getConnection();var s=c.createStatement()){
            s.execute("CREATE SCHEMA ONE");s.execute("CREATE SCHEMA TWO");
            s.execute("CREATE TABLE ONE.CUSTOMERS (ID INTEGER PRIMARY KEY, NAME VARCHAR(80))");
            s.execute("CREATE TABLE TWO.CUSTOMERS (CODE VARCHAR(20))");
        }
        var sources=mock(DataSourceStore.class);var provider=mock(ExecutionDataSourceProvider.class);
        when(sources.find(1)).thenReturn(Optional.of(new DataSourceDefinition("test","POSTGRESQL:general","localhost",5432,"test","test",null,Map.of())));
        when(provider.get(1)).thenReturn(db);
        var metadata=new ObjectMetadata(sources,provider);var tables=metadata.tables(1,null,"ONE");
        var table=tables.stream().filter(t->t.name().equals("CUSTOMERS")).findFirst().orElseThrow();
        var fields=metadata.fields(new ObjectStore.Identity(1,table.catalog(),table.schema(),table.name(),table.type()));
        assertEquals(2,fields.size());assertEquals("YES",fields.getFirst().primaryKey());assertEquals("NO",fields.getFirst().nullable());
        assertThrows(IllegalArgumentException.class,()->metadata.fields(new ObjectStore.Identity(1,table.catalog(),"TWO","MISSING","TABLE")));
    }
}
