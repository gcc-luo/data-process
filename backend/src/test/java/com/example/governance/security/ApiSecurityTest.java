package com.example.governance.security;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GovernanceApplication.class)
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    @Test void publicHealthIsAvailableButManagementRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(post("/api/workflows/validate").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tasks\":[\"a\"],\"edges\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
