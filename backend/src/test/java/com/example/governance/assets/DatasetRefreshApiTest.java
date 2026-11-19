package com.example.governance.assets;
import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:dataset_refresh;DB_CLOSE_DELAY=-1", "governance.realtime-poll-ms=3600000", "governance.schedule-poll-ms=3600000"})
class DatasetRefreshApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder; @Autowired CredentialCipher cipher;
    @MockitoBean DatabaseInspector inspector;
    @Autowired com.example.governance.query.DatasetPolicyStore policies;
    @Test void refreshRequiresPermissionAddsOnlyDiscoveredFieldsAndDoesNotGrantThem() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (10,'department')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (10,'manager',?,10,TRUE,'v')",encoder.encode("password"));
        jdbc.update("INSERT INTO roles(id,name) VALUES (10,'manager')"); jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (10,10)");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (15,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u',?)",cipher.encrypt("secret"));
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name,department_field) VALUES (20,15,'dataset','orders','dept')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (20,0,'dept','BIGINT',FALSE),(20,1,'id','BIGINT',FALSE)");
        jdbc.update("INSERT INTO dataset_role_rules(dataset_id,role_id,scope) VALUES (20,10,'OWN')");
        jdbc.update("INSERT INTO dataset_role_fields(dataset_id,role_id,field_name,access_mode) VALUES (20,10,'id','FULL')");
        when(inspector.columns(any(),eq("secret"),isNull(),isNull(),eq("orders"))).thenReturn(List.of(new DatabaseInspector.Column("dept","BIGINT",0,false),new DatabaseInspector.Column("id","BIGINT",0,false),new DatabaseInspector.Column("secret","VARCHAR",20,true)));
        var session=(org.springframework.mock.web.MockHttpSession)mvc.perform(post("/api/login").with(csrf()).param("username","manager").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/datasets/20/refresh").session(session).with(csrf())).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (10,'datasource.source.read'),(10,'datasource.source.create'),(10,'datasource.source.update'),(10,'datasource.source.delete'),(10,'datasource.source.test'),(10,'datasource.dataset.read'),(10,'datasource.dataset.create'),(10,'datasource.dataset.delete'),(10,'datasource.dataset.refresh'),(10,'datasource.dataset.manage_policy'),(10,'datasource.query.read')");
        mvc.perform(post("/api/datasets/20/refresh").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM dataset_fields WHERE dataset_id=20",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dataset_role_fields WHERE field_name='secret'",Integer.class));
        assertFalse(policies.resolve(10,20).policy().fields().containsKey("secret"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dataset.refresh'",Integer.class));
        when(inspector.columns(any(),any(),isNull(),isNull(),eq("orders"))).thenReturn(List.of(new DatabaseInspector.Column("id","VARCHAR",0,false)));
        mvc.perform(post("/api/datasets/20/refresh").session(session).with(csrf())).andExpect(status().isConflict());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM dataset_fields WHERE dataset_id=20",Integer.class));
    }
}
