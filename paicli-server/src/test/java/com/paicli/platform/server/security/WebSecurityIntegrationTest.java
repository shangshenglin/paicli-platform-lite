package com.paicli.platform.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "paicli.data-dir=target/test-data/web-security",
        "paicli.workspace-root=target/test-data/web-security/workspaces",
        "paicli.worker-count=1",
        "paicli.worker-poll-millis=1000",
        "paicli.model.provider=demo",
        "paicli.web.enabled=false",
        "paicli.security.api-key=test-secret",
        "paicli.security.require-api-key=true",
        "paicli.security.protect-management=true"
})
@AutoConfigureMockMvc
@DirtiesContext
class WebSecurityIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void protectsApiManagementAndOpenApiWithTheSameKey() throws Exception {
        mvc.perform(get("/v1/system/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());

        mvc.perform(get("/v1/system/info").header("X-API-Key", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("paicli-platform-lite"))
                .andExpect(jsonPath("$.phase").value(10));
        mvc.perform(get("/actuator/health").header("X-API-Key", "test-secret"))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs").header("X-API-Key", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.info.title").value("PaiCLI Platform Lite API"));
    }

    @Test
    void consoleUsesSecurityHeadersAndSessionScopedCredentialStorage() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "PAICLI_MODEL_API_KEY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"templateForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"profileForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"scheduleForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"notificationForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"memoryMergeForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"memoryRevisionForm\"")));
        mvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "sessionStorage.getItem('paicli_api_key')")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "response.status === 401")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "openConnectionSettings")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("localStorage.getItem('paicli_api_key')"))));
    }

    @Test
    void resolvesTaskTemplateByTheIdReturnedToConsole() throws Exception {
        String templates = mvc.perform(get("/v1/productivity/templates")
                        .param("projectKey", "template-regression")
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(templates).get(0).path("id").asText();

        mvc.perform(post("/v1/productivity/templates/{id}/resolve", id)
                        .param("projectKey", "template-regression")
                        .header("X-API-Key", "test-secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.id").value(id))
                .andExpect(jsonPath("$.prompt").isNotEmpty());

        String scheduleName = "工作日检查-" + System.nanoTime();
        mvc.perform(post("/v1/productivity/schedules")
                        .header("X-API-Key", "test-secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"template-regression","name":"%s",
                                 "templateId":"%s","scheduleType":"CRON",
                                 "scheduleValue":"0 0 9 * * MON-FRI","variables":{},"enabled":true}
                                """.formatted(scheduleName, id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateId").value(id))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
    }
}
