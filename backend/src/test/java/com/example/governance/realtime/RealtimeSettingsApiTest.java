package com.example.governance.realtime;
import com.example.governance.GovernanceApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:realtime_settings_api;DB_CLOSE_DELAY=-1")
class RealtimeSettingsApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder; @Autowired RealtimeJobStore jobs;
    @Test void editsOnlyWhenAuthorizedAndStoppedWithoutChangingCursorOrAllowingMappingChange() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (1001,'dept')"); jdbc.update("INSERT INTO roles(id,name) VALUES (1001,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1001,'rt-settings',?,1001,TRUE,'v')",encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (1001,1001)");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,host,port,database_name,username,password_cipher) VALUES (1001,'db','MYSQL','localhost',3306,'db','u','cipher')");
        var mapping=new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",10);
        long id=jobs.create("job",new RealtimeJobStore.JobConfig(1001,1001,mapping,5));
        var session=(MockHttpSession)mvc.perform(post("/api/login").with(csrf()).param("username","rt-settings").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        String url="/api/realtime-jobs/"+id+"/settings", body="{\"intervalSeconds\":30,\"batchSize\":20}";
        mvc.perform(put(url).session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (1001,'workflow.manage')");
        mvc.perform(put(url).session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(url).session(session).with(csrf()).contentType("application/json").content("{\"intervalSeconds\":30,\"batchSize\":20,\"sourceId\":999}")).andExpect(status().isBadRequest());
        mvc.perform(put(url).session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNoContent());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='realtime.settings' AND actor_id=1001",Integer.class));
        mvc.perform(get("/api/realtime-jobs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].intervalSeconds").value(30)).andExpect(jsonPath("$[0].batchSize").value(20));
        jobs.start(id,java.time.Instant.now());
        mvc.perform(put(url).session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(put("/api/realtime-jobs/999999/settings").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
    }
}
