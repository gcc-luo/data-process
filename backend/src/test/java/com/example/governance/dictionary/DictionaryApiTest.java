package com.example.governance.dictionary;

import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DictionaryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM dictionary_entries");jdbc.update("DELETE FROM dictionary_directories");
        jdbc.update("DELETE FROM audit_events");jdbc.update("DELETE FROM user_roles WHERE user_id=101");jdbc.update("DELETE FROM role_permissions WHERE role_id=101");jdbc.update("DELETE FROM users WHERE id=101");jdbc.update("DELETE FROM roles WHERE id=101");jdbc.update("DELETE FROM departments WHERE id=101");
        jdbc.update("INSERT INTO departments(id,name) VALUES (101,'dictionary test')");jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (101,'dictionary_tester',?,101,TRUE,'v')",encoder.encode("password"));jdbc.update("INSERT INTO roles(id,name) VALUES (101,'dictionary tester')");jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (101,101)");
    }
    MockHttpSession login() throws Exception { return (MockHttpSession)mvc.perform(post("/api/login").with(csrf()).param("username","dictionary_tester").param("password","password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false); }
    void grant(String... codes) { for(String code:codes)jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (101,?)",code); }
    @Test void templateIncludesSeparateGuideAndSafeExamples() throws Exception {
        grant("datasource.dictionary.read","datasource.dictionary.import");var session=login();
        byte[] bytes=mvc.perform(get("/api/dictionary/template").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))){
            assertEquals("数据字典",workbook.getSheetAt(0).getSheetName());assertEquals(0,workbook.getSheetAt(0).getLastRowNum());
            var guide=workbook.getSheet("填写说明与示例");assertNotNull(guide);assertTrue(guide.getRow(4).getCell(1).getStringCellValue().contains("不是填写 1、2、3"));
            assertEquals("varchar",guide.getRow(15).getCell(4).getStringCellValue());assertEquals("integer",guide.getRow(16).getCell(4).getStringCellValue());assertEquals("decimal",guide.getRow(17).getCell(4).getStringCellValue());
        }
        var file=new MockMultipartFile("file","template.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes);
        mvc.perform(multipart("/api/dictionary/import").file(file).session(session).with(csrf())).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dictionary_entries",Integer.class));
    }
    @Test void permissionImportAndChineseAudit() throws Exception {
        var session=login();mvc.perform(get("/api/dictionary/entries").session(session)).andExpect(status().isForbidden());
        grant("datasource.dictionary.read","datasource.dictionary.create","datasource.dictionary.import");
        var created=mvc.perform(post("/api/dictionary/directories").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"基础数据\"}")).andExpect(status().isOk()).andReturn();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet();String[] header={"目录路径","字典标识","字典名","字典含义","类型","长度","精度","描述"};var h=sheet.createRow(0);for(int c=0;c<header.length;c++)h.createCell(c).setCellValue(header[c]);
            for(int i=1;i<=2;i++){var row=sheet.createRow(i);String[] values={"基础数据","user_status","状态","账号状态","varchar","20","",""};for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}workbook.write(out);
            var file=new MockMultipartFile("file","dictionary.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
            mvc.perform(multipart("/api/dictionary/import").file(file).session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(1)).andExpect(jsonPath("$.failed").value(1)).andExpect(jsonPath("$.errors[0].row").value(3));
        }
        mvc.perform(get("/api/dictionary/entries").session(session).param("directoryId",Long.toString(id)).param("search","user")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        assertEquals("导入数据字典",com.example.governance.security.AuditCatalog.actionLabel("dictionary.import"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dictionary.import' AND message LIKE '%成功 1%失败 1%'",Integer.class));
        var bad=new MockMultipartFile("file","bad.xlsx","application/octet-stream",new byte[]{1,2,3});
        mvc.perform(multipart("/api/dictionary/import").file(bad).session(session).with(csrf())).andExpect(status().isBadRequest());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='dictionary.import' AND result='FAILURE'",Integer.class));
        mvc.perform(delete("/api/dictionary/directories/"+id).session(session).with(csrf())).andExpect(status().isForbidden());
    }
}
