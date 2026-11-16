package com.example.governance.mysql;
import com.example.governance.workflow.CamelTaskRunner;
import com.example.governance.workflow.TaskVersionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test", matches="true")
class MySqlTaskParametersTest {
    @Test void bindsMultipleValuesAndBusinessDateInOrder() throws Exception {
        var db=new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password", ""));
        var jdbc=new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE parameter_rows (n DECIMAL(10,2), label VARCHAR(40), enabled BOOLEAN, optional_value VARCHAR(40), business_date DATE) ENGINE=InnoDB");
        String config="""
          {"sourceId":1,"sql":"INSERT INTO parameter_rows(n,label,enabled,optional_value,business_date) VALUES (?,?,?,?,?)","parameters":[{"type":"VALUE","value":42.5},{"type":"VALUE","value":"hello"},{"type":"VALUE","value":true},{"type":"VALUE","value":null},{"type":"BUSINESS_DATE"}]}
          """;
        try(var runner=new CamelTaskRunner(id -> db)) { runner.execute(new TaskVersionStore.Version(1,1,"SQL",config),LocalDate.of(2026,11,16)); }
        var row=jdbc.queryForMap("SELECT n,label,enabled,optional_value,business_date FROM parameter_rows");
        assertEquals(0,new java.math.BigDecimal("42.5").compareTo((java.math.BigDecimal)row.get("n")));
        assertEquals("hello",row.get("label")); assertEquals(Boolean.TRUE,jdbc.queryForObject("SELECT enabled FROM parameter_rows",Boolean.class)); assertNull(row.get("optional_value"));
        assertEquals(LocalDate.of(2026,11,16),((java.sql.Date)row.get("business_date")).toLocalDate());
    }
}
