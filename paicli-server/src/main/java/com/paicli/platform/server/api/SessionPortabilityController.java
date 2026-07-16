package com.paicli.platform.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/sessions")
public class SessionPortabilityController {
    private final SqliteRuntimeStore store;private final ObjectMapper mapper;
    public SessionPortabilityController(SqliteRuntimeStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper;}

    @GetMapping("/{sessionId}/export")
    public ResponseEntity<?> export(@PathVariable String sessionId,@RequestParam(defaultValue="markdown")String format,
                                    @RequestParam(defaultValue="false")boolean redactSecrets)throws Exception{
        SessionRecord session=store.findSession(sessionId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"session not found"));
        String normalized=format.trim().toLowerCase();
        if(normalized.equals("markdown")){
            String text=markdown(session,redactSecrets);return ResponseEntity.ok().contentType(new MediaType("text","markdown",StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+safe(session.title())+".md\"").body(text);
        }
        Map<String,Object> bundle=bundle(session,normalized.equals("audit"));
        JsonNode output=redactSecrets?mapper.readTree(redact(mapper.writeValueAsString(bundle))):mapper.valueToTree(bundle);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+safe(session.title())+"-"+normalized+".json\"").body(output);
    }

    @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED)
    public SessionRecord importSession(@RequestBody ApiDtos.SessionImportRequest request)throws Exception{
        String payload=Boolean.TRUE.equals(request.redactSecrets())?redact(request.payload()):request.payload();
        JsonNode root=mapper.readTree(payload);JsonNode source=root.path("session");
        String title=source.path("title").asText("导入对话");
        SessionRecord session=store.createSession(title+"（导入）",request.projectKey(),null);
        try{
            for(JsonNode message:root.path("messages")){
                String role=message.path("role").asText("user");
                if(!List.of("user","assistant","tool","summary").contains(role))role="user";
                store.appendMessage(session.id(),null,role,message.path("content").asText(""));
            }
            return session;
        }catch(Exception e){store.deleteSession(session.id());throw e;}
    }

    private Map<String,Object> bundle(SessionRecord session,boolean audit){
        Map<String,Object> root=new LinkedHashMap<>();root.put("formatVersion",1);root.put("exportType",audit?"audit":"json");root.put("session",session);
        root.put("messages",store.messages(session.id()));List<Map<String,Object>> runs=new ArrayList<>();
        for(var run:store.runsForSession(session.id())){Map<String,Object> item=new LinkedHashMap<>();item.put("run",run);item.put("events",store.events(run.id(),0));
            item.put("artifacts",store.artifactsForRun(run.id()));item.put("attachments",store.attachmentsForRun(run.id()));
            if(audit){item.put("toolCalls",store.toolCallsForRun(run.id()));item.put("approvals",store.approvalsForRun(run.id()));}
            runs.add(item);}root.put("runs",runs);return root;
    }
    private String markdown(SessionRecord session,boolean redact){StringBuilder out=new StringBuilder("# ").append(session.title()).append("\n\n");
        out.append("- 项目：").append(session.projectKey()).append("\n- Session：").append(session.id()).append("\n\n");
        for(var message:store.messages(session.id())){out.append("## ").append(switch(message.role()){case"user"->"用户";case"assistant"->"PaiCLI";case"tool"->"工具";default->"摘要";}).append("\n\n").append(message.content()).append("\n\n");}
        out.append("## Run 与 Artifact 清单\n\n");for(var run:store.runsForSession(session.id())){out.append("- ").append(run.id()).append(" · ").append(run.status()).append(" · step ").append(run.currentStep()).append("\n");for(var artifact:store.artifactsForRun(run.id()))out.append("  - Artifact：").append(artifact.name()).append(" · ").append(artifact.sha256()).append("\n");}
        return redact?redact(out.toString()):out.toString();}
    private static String redact(String value){return value.replaceAll("(?i)(authorization|api[_-]?key|access[_-]?token|secret)(\\s*[=:]\\s*|\\\"\\s*:\\s*\\\")([^\\s,;\\\"]+)","$1$2[REDACTED]");}
    private static String safe(String value){String result=value==null?"session":value.replaceAll("[\\\\/:*?\"<>|]","_").trim();return result.isBlank()?"session":result;}
}
