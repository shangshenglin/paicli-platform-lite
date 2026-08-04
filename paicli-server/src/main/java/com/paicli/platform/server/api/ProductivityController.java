package com.paicli.platform.server.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.collaboration.CollaborationToolProvider;
import com.paicli.platform.server.plan.PlanToolProvider;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/v1/productivity")
public class ProductivityController {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_.-]{0,79})}");
    private static final List<ModelSeed> MODEL_SEEDS = List.of(
            new ModelSeed("DeepSeek V4 Flash", "https://api.deepseek.com",
                    "PAICLI_DEEPSEEK_API_KEY", "deepseek-v4-flash", 1_000_000, 16_384),
            new ModelSeed("Kimi K3", "https://api.moonshot.cn/v1",
                    "PAICLI_KIMI_API_KEY", "kimi-k3", 1_000_000, 131_072)
    );
    private final ProductivityStore productivity;
    private final SqliteRuntimeStore runtime;
    private final ObjectMapper mapper;
    private final ModelClient modelClient;
    private final ToolRouter tools;

    public ProductivityController(ProductivityStore productivity, SqliteRuntimeStore runtime,
                                  ObjectMapper mapper, ModelClient modelClient, ToolRouter tools) {
        this.productivity = productivity; this.runtime = runtime; this.mapper = mapper;
        this.modelClient = modelClient; this.tools = tools;
    }

    @GetMapping("/templates")
    public List<ProductivityStore.TaskTemplate> templates(@RequestParam(defaultValue = "default") String projectKey) {
        ensureBuiltIns(projectKey); return productivity.templates(projectKey);
    }

    @PostMapping("/templates") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.TaskTemplate createTemplate(@Valid @RequestBody ApiDtos.TaskTemplateRequest request) {
        return saveTemplate(null, request);
    }

    @PutMapping("/templates/{id}")
    public ProductivityStore.TaskTemplate updateTemplate(@PathVariable String id,
                                                          @Valid @RequestBody ApiDtos.TaskTemplateRequest request) {
        return saveTemplate(id, request);
    }

    @DeleteMapping("/templates/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable String id) {
        if (!productivity.deleteTemplate(id)) throw notFound("template");
    }

    @PostMapping("/templates/{idOrShortcut}/resolve")
    public Map<String, Object> resolveTemplate(@PathVariable String idOrShortcut,
                                               @RequestParam(defaultValue = "default") String projectKey,
                                               @RequestBody(required = false) ApiDtos.TemplateResolveRequest request) {
        ensureBuiltIns(projectKey);
        var template = productivity.markTemplateUsed(projectKey, idOrShortcut);
        Map<String,String> variables = readMap(template.variablesJson());
        if (request != null && request.variables() != null) variables.putAll(request.variables());
        String prompt = render(template.prompt(), variables);
        return Map.of("template", template, "prompt", prompt, "variables", variables,
                "modelProfileId", template.modelProfileId() == null ? "" : template.modelProfileId());
    }

    @GetMapping("/model-profiles")
    public List<ProductivityStore.ModelProfile> modelProfiles(@RequestParam(defaultValue="default") String projectKey) {
        ensureModelBuiltIns(projectKey);
        return productivity.modelProfiles(projectKey);
    }

    @PostMapping("/model-profiles/starter-pack")
    public List<ProductivityStore.ModelProfile> installModelStarterPack(
            @RequestParam(defaultValue="default") String projectKey) {
        ensureModelBuiltIns(projectKey);
        return productivity.modelProfiles(projectKey);
    }

    @PostMapping("/model-profiles") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.ModelProfile createProfile(@Valid @RequestBody ApiDtos.ModelProfileRequest request) {
        return saveProfile(null, request);
    }

    @PutMapping("/model-profiles/{id}")
    public ProductivityStore.ModelProfile updateProfile(@PathVariable String id,
                                                         @Valid @RequestBody ApiDtos.ModelProfileRequest request) {
        return saveProfile(id, request);
    }

    @DeleteMapping("/model-profiles/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable String id) {
        if (!productivity.deleteModelProfile(id)) throw notFound("profile");
    }

    @GetMapping("/agent-profiles")
    public List<ProductivityStore.AgentProfile> agentProfiles(@RequestParam(defaultValue="default") String projectKey) {
        ensureAgentBuiltIns(projectKey);
        return productivity.agentProfiles(projectKey);
    }

    @PostMapping("/agent-profiles/starter-pack")
    public List<ProductivityStore.AgentProfile> installAgentStarterPack(
            @RequestParam(defaultValue="default") String projectKey) {
        ensureAgentBuiltIns(projectKey);
        return productivity.agentProfiles(projectKey);
    }

    @PostMapping("/agent-profiles") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.AgentProfile createAgentProfile(@Valid @RequestBody ApiDtos.AgentProfileRequest request) {
        return saveAgentProfile(null, request);
    }

    @PutMapping("/agent-profiles/{id}")
    public ProductivityStore.AgentProfile updateAgentProfile(@PathVariable String id,
                                                             @Valid @RequestBody ApiDtos.AgentProfileRequest request) {
        return saveAgentProfile(id, request);
    }

    @DeleteMapping("/agent-profiles/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgentProfile(@PathVariable String id) {
        if (!productivity.deleteAgentProfile(id)) throw notFound("agent profile");
    }

    @GetMapping("/agent-teams")
    public List<ProductivityStore.AgentTeam> agentTeams(
            @RequestParam(defaultValue="default") String projectKey) {
        return productivity.agentTeams(projectKey);
    }

    @PostMapping("/agent-teams") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.AgentTeam createAgentTeam(
            @Valid @RequestBody ApiDtos.AgentTeamRequest request) {
        return saveAgentTeam(null, request);
    }

    @PutMapping("/agent-teams/{id}")
    public ProductivityStore.AgentTeam updateAgentTeam(
            @PathVariable String id, @Valid @RequestBody ApiDtos.AgentTeamRequest request) {
        return saveAgentTeam(id, request);
    }

    @DeleteMapping("/agent-teams/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgentTeam(@PathVariable String id) {
        if (!productivity.deleteAgentTeam(id)) throw notFound("agent team");
    }

    @GetMapping("/agent-profiles/templates")
    public List<Map<String, Object>> agentProfileTemplates() {
        return AGENT_SEEDS.stream().map(seed -> Map.<String, Object>of(
                "key", seed.key(), "version", seed.version(), "name", seed.name(),
                "description", seed.description(), "role", seed.role(), "tools", withPlanTools(seed.tools()),
                "approvalPolicy", seed.approval(), "outputSchema", seed.outputSchema())).toList();
    }

    @PostMapping("/agent-profiles/{id}/restore-template")
    public ProductivityStore.AgentProfile restoreAgentTemplate(@PathVariable String id) {
        var existing = productivity.findAgentProfile(id).orElseThrow(() -> notFound("agent profile"));
        var seed = findAgentSeed(existing.templateKey(), existing.name());
        if (seed == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "agent profile is not linked to a built-in template");
        try {
            return productivity.saveAgentProfile(existing.id(), existing.projectKey(), seed.name(), seed.description(),
                    seed.prompt(), existing.modelProfileId(), mapper.writeValueAsString(withPlanTools(seed.tools())),
                    mapper.writeValueAsString(List.of()), seed.outputSchema(), seed.role(), seed.handoff(),
                    "PROJECT", seed.approval(), existing.thinkingMode(), existing.reasoningEffort(),
                    existing.executionShell(), existing.enabled(), seed.key(), seed.version());
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalArgumentException("restore agent template failed", e);
        }
    }

    @PostMapping("/agent-profiles/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.AgentProfile copyAgentProfile(@PathVariable String id,
                                                           @RequestBody(required = false) ApiDtos.AgentProfileCopyRequest request) {
        var source = productivity.findAgentProfile(id).orElseThrow(() -> notFound("agent profile"));
        String project = request == null || request.projectKey() == null || request.projectKey().isBlank()
                ? source.projectKey() : request.projectKey();
        String name = request == null || request.name() == null || request.name().isBlank()
                ? source.name() + " 副本" : request.name();
        try {
            return productivity.saveAgentProfile(null, project, name, source.description(), source.systemPrompt(),
                    source.modelProfileId(), withPlanToolsJson(source.toolNamesJson()),
                    source.skillNamesJson(), source.outputSchema(),
                    source.collaborationRole(), source.handoffPolicy(), source.workspaceScope(), source.approvalPolicy(),
                    source.thinkingMode(), source.reasoningEffort(), source.executionShell(), source.enabled(),
                    source.templateKey(), source.templateVersion());
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalArgumentException("copy agent profile failed", e);
        }
    }

    @GetMapping("/estimate")
    public Map<String,Object> estimate(@RequestParam String sessionId,
                                       @RequestParam(required=false) String modelProfileId,
                                       @RequestParam(defaultValue="0") int inputChars) {
        var session=runtime.findSession(sessionId).orElseThrow(()->notFound("session"));
        var profile=productivity.resolveModelProfile(session.projectKey(),modelProfileId).orElse(null);
        long chars=runtime.activeMessages(sessionId).stream().mapToLong(value->value.content().length()).sum()+Math.max(0,inputChars);
        long estimatedTokens=Math.max(1,(chars+3)/4);
        int contextLimit=profile==null?0:profile.maxContextTokens(),outputLimit=profile==null?0:profile.maxOutputTokens();
        double risk=contextLimit<=0?0:(double)(estimatedTokens+outputLimit)/contextLimit;
        double cost=profile==null||profile.localModel()?0:(estimatedTokens/1_000_000d*profile.inputPrice()+outputLimit/1_000_000d*profile.outputPrice());
        return Map.of("estimatedContextTokens",estimatedTokens,"maxContextTokens",contextLimit,
                "maxOutputTokens",outputLimit,"estimatedMaxCost",cost,"risk",risk,
                "warning",risk>=.9?"上下文接近上限":risk>=.75?"上下文使用较高":cost>1?"预计成本较高":"");
    }

    @GetMapping("/usage")
    public ProductivityStore.UsageSummary usage(@RequestParam(defaultValue="default") String projectKey,
                                                @RequestParam(defaultValue="30") int days) {
        return productivity.usage(projectKey,days);
    }

    @GetMapping("/budget")
    public ProductivityStore.BudgetPolicy budget(@RequestParam(defaultValue="default") String projectKey){return productivity.budget(projectKey);}

    @PutMapping("/budget")
    public ProductivityStore.BudgetPolicy saveBudget(@RequestParam(defaultValue="default") String projectKey,
                                                      @RequestBody ApiDtos.BudgetRequest request){
        return productivity.saveBudget(projectKey,value(request.dailyTokens()),value(request.monthlyTokens()),
                value(request.dailyCost()),value(request.monthlyCost()),request.warnRatio()==null?.8:request.warnRatio(),
                request.maxConcurrentRuns()==null?4:request.maxConcurrentRuns());
    }

    @GetMapping("/queue")
    public List<ProductivityStore.QueueItem> queue(@RequestParam(defaultValue="default") String projectKey){return productivity.queue(projectKey);}

    @PatchMapping("/queue/{runId}/priority")
    public Map<String,Object> priority(@PathVariable String runId,@Valid @RequestBody ApiDtos.QueuePriorityRequest request){
        if(!productivity.setPriority(runId,request.priority()))throw new ResponseStatusException(HttpStatus.CONFLICT,"only queued runs can change priority");
        return Map.of("runId",runId,"priority",request.priority());
    }

    @PostMapping("/queue/{runId}/requeue")
    public Map<String,Object> requeue(@PathVariable String runId){
        if(!productivity.requeue(runId))throw new ResponseStatusException(HttpStatus.CONFLICT,"only failed or canceled runs can be requeued");
        runtime.appendEvent(runId,"run.requeued","{\"source\":\"productivity-console\"}");
        return Map.of("runId",runId,"requeued",true);
    }

    @PostMapping("/queue/batch")
    @io.swagger.v3.oas.annotations.Operation(summary = "Batch-manage Run queue records",
            description = "CANCEL, REQUEUE and PRIORITY update queue state. DELETE permanently removes up to 100 "
                    + "terminal Runs and their persisted runtime records in one transaction; any missing Run, active "
                    + "target, or active related delegation Run rolls back the complete delete batch.")
    public Map<String,Object> batch(@Valid @RequestBody ApiDtos.QueueBatchRequest request){
        String action=request.action().trim().toUpperCase();
        if("DELETE".equals(action)){
            List<String> deleted=runtime.deleteRuns(request.runIds());
            return Map.of("action",action,"changed",deleted,"deletedCount",deleted.size());
        }
        List<String> changed=new ArrayList<>();
        for(String runId:request.runIds().stream().filter(v->v!=null&&!v.isBlank()).distinct().limit(100).toList()){
            boolean ok=switch(action){
                case "CANCEL"->{boolean canceled=runtime.cancelRunTree(runId).contains(runId);modelClient.cancel(runId);tools.cancel(runId);yield canceled;}
                case "REQUEUE"->{boolean requeued=productivity.requeue(runId);if(requeued)runtime.appendEvent(runId,"run.requeued","{\"source\":\"productivity-console\"}");yield requeued;}
                case "PRIORITY"->productivity.setPriority(runId,request.priority()==null?0:request.priority());
                default->throw new IllegalArgumentException("action must be CANCEL, REQUEUE, PRIORITY, or DELETE");};
            if(ok)changed.add(runId);
        }
        return Map.of("action",action,"changed",changed);
    }

    @GetMapping("/schedules")
    public List<ProductivityStore.ScheduledTask> schedules(@RequestParam(defaultValue="default")String projectKey){return productivity.schedules(projectKey);}
    @PostMapping("/schedules") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.ScheduledTask createSchedule(@Valid @RequestBody ApiDtos.ScheduledTaskRequest r){return saveSchedule(null,r);}
    @PutMapping("/schedules/{id}")
    public ProductivityStore.ScheduledTask updateSchedule(@PathVariable String id,@Valid @RequestBody ApiDtos.ScheduledTaskRequest r){return saveSchedule(id,r);}
    @DeleteMapping("/schedules/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(@PathVariable String id){
        if(!productivity.deleteSchedule(id)) throw notFound("schedule");
    }

    @GetMapping("/notifications")
    public List<ProductivityStore.NotificationChannel> notifications(@RequestParam(defaultValue="default")String projectKey){return productivity.notificationChannels(projectKey);}
    @PostMapping("/notifications") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.NotificationChannel createNotification(@Valid @RequestBody ApiDtos.NotificationChannelRequest r){return saveNotification(null,r);}
    @PutMapping("/notifications/{id}")
    public ProductivityStore.NotificationChannel updateNotification(@PathVariable String id,@Valid @RequestBody ApiDtos.NotificationChannelRequest r){return saveNotification(id,r);}
    @DeleteMapping("/notifications/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotification(@PathVariable String id){
        if(!productivity.deleteNotification(id)) throw notFound("notification");
    }

    private ProductivityStore.TaskTemplate saveTemplate(String id,ApiDtos.TaskTemplateRequest r){
        try{return productivity.saveTemplate(id,r.projectKey(),r.name(),r.shortcut(),r.prompt(),mapper.writeValueAsString(r.variables()==null?Map.of():r.variables()),r.attachmentRequirements(),String.join(",",r.allowedTools()==null?List.of():r.allowedTools()),r.modelProfileId());}
        catch(Exception e){throw e instanceof RuntimeException runtime?runtime:new IllegalArgumentException("invalid template",e);}
    }
    private ProductivityStore.ModelProfile saveProfile(String id,ApiDtos.ModelProfileRequest r){return productivity.saveModelProfile(id,r.projectKey(),r.name(),r.baseUrl(),r.apiKeyEnv(),r.model(),r.fallbackModel(),r.maxContextTokens()==null?128000:r.maxContextTokens(),r.maxOutputTokens()==null?4096:r.maxOutputTokens(),r.inputPrice()==null?0:r.inputPrice(),r.outputPrice()==null?0:r.outputPrice(),Boolean.TRUE.equals(r.localModel()),Boolean.TRUE.equals(r.makeDefault()));}
    private ProductivityStore.AgentProfile saveAgentProfile(String id, ApiDtos.AgentProfileRequest r) {
        try {
            if (r.modelProfileId() != null && !r.modelProfileId().isBlank()
                    && productivity.resolveModelProfile(r.projectKey(), r.modelProfileId()).isEmpty()) {
                throw notFound("model profile");
            }
            String role = r.collaborationRole() == null || r.collaborationRole().isBlank()
                    ? "EXPERT" : r.collaborationRole().trim().toUpperCase();
            List<String> tools = r.toolNames() == null || r.toolNames().isEmpty()
                    ? defaultToolsForRole(role) : sanitizeToolsForRole(role, r.toolNames());
            tools = withPlanTools(tools);
            String approval = r.approvalPolicy() == null || r.approvalPolicy().isBlank()
                    ? defaultApprovalForRole(role) : r.approvalPolicy();
            return productivity.saveAgentProfile(id, r.projectKey(), r.name(), r.description(), r.systemPrompt(),
                    r.modelProfileId(), mapper.writeValueAsString(tools),
                    mapper.writeValueAsString(r.skillNames() == null ? List.of() : r.skillNames()),
                    r.outputSchema(), r.collaborationRole(), r.handoffPolicy(), r.workspaceScope(),
                    approval, r.thinkingMode(), r.reasoningEffort(), r.executionShell(),
                    r.enabled() == null || r.enabled(), "", 0);
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalArgumentException("invalid agent profile", e);
        }
    }

    private ProductivityStore.AgentTeam saveAgentTeam(String id, ApiDtos.AgentTeamRequest request) {
        var leader = productivity.resolveAgentProfile(request.projectKey(), request.leaderAgentProfileId())
                .orElseThrow(() -> notFound("leader agent profile"));
        if (!"LEADER".equalsIgnoreCase(leader.collaborationRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agent team leader must use the LEADER collaboration role");
        }
        List<String> members = request.memberAgentProfileIds() == null ? List.of()
                : request.memberAgentProfileIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .filter(value -> !value.equals(leader.id()))
                .distinct().limit(20).toList();
        for (String memberId : members) {
            productivity.resolveAgentProfile(request.projectKey(), memberId)
                    .orElseThrow(() -> notFound("agent team member"));
        }
        try {
            return productivity.saveAgentTeam(id, request.projectKey(), request.name(),
                    request.description(), leader.id(), mapper.writeValueAsString(members),
                    request.maxExperts() == null ? Math.max(1, members.size()) : request.maxExperts(),
                    request.maxDepth() == null ? 1 : request.maxDepth(),
                    Boolean.TRUE.equals(request.requireReviewer()),
                    Boolean.TRUE.equals(request.requireRunner()),
                    request.teamInstructions(),
                    mapper.writeValueAsString(request.memberRoles() == null ? Map.of() : request.memberRoles()),
                    mapper.writeValueAsString(request.capabilityTags() == null ? List.of() : request.capabilityTags()),
                    request.routingPolicy(), request.completionPolicy(), request.fallbackAgentProfileId(),
                    request.maxConcurrency() == null ? Math.max(1, members.size()) : request.maxConcurrency(),
                    request.enabled() == null || request.enabled());
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalArgumentException("invalid agent team", e);
        }
    }
    private ProductivityStore.ScheduledTask saveSchedule(String id,ApiDtos.ScheduledTaskRequest r){
        try{
            productivity.findTemplate(r.projectKey(),r.templateId()).orElseThrow(()->notFound("template"));
            if(!blank(r.modelProfileId()) && productivity.resolveModelProfile(r.projectKey(),r.modelProfileId()).isEmpty()) {
                throw notFound("model profile");
            }
            if(!blank(r.agentProfileId()) && productivity.resolveAgentProfile(r.projectKey(),r.agentProfileId()).isEmpty()) {
                throw notFound("agent profile");
            }
            if(!blank(r.agentProfileId()) && !blank(r.agentTeamId())) {
                throw new IllegalArgumentException("a schedule can select either an agent profile or an agent team, not both");
            }
            if(!blank(r.agentTeamId())) {
                var team=productivity.findAgentTeam(r.agentTeamId()).filter(value -> value.enabled()
                        && value.projectKey().equals(r.projectKey())).orElseThrow(()->notFound("agent team"));
                var leader=productivity.resolveAgentProfile(r.projectKey(),team.leaderAgentProfileId());
                if(leader.isEmpty() || !"LEADER".equalsIgnoreCase(leader.get().collaborationRole())) {
                    throw new IllegalArgumentException("agent team must have an enabled LEADER agent profile");
                }
            }
            String type=r.scheduleType().trim().toUpperCase();
            Instant next=firstScheduleRun(type,r.scheduleValue(),r.nextRunAt());
            return productivity.saveSchedule(id,r.projectKey(),r.name(),r.templateId(),type,r.scheduleValue(),
                    mapper.writeValueAsString(r.variables()==null?Map.of():r.variables()),r.modelProfileId(),
                    r.agentProfileId(),r.agentTeamId(),r.enabled()==null||r.enabled(),next);
        }
        catch(Exception e){throw e instanceof RuntimeException runtime?runtime:new IllegalArgumentException("invalid schedule",e);}
    }
    private ProductivityStore.NotificationChannel saveNotification(String id,ApiDtos.NotificationChannelRequest r){return productivity.saveNotification(id,r.projectKey(),r.name(),r.type(),r.endpoint(),r.secretEnv(),String.join(",",r.events()),r.enabled()==null||r.enabled());}
    private synchronized void ensureModelBuiltIns(String project) {
        var existingNames = productivity.modelProfiles(project).stream()
                .map(ProductivityStore.ModelProfile::name)
                .collect(java.util.stream.Collectors.toSet());
        for (var seed : MODEL_SEEDS) {
            if (existingNames.contains(seed.name())) continue;
            productivity.saveModelProfile(null, project, seed.name(), seed.baseUrl(), seed.apiKeyEnv(),
                    seed.model(), "", seed.maxContextTokens(), seed.maxOutputTokens(),
                    0, 0, false, false);
            existingNames.add(seed.name());
        }
    }
    private void ensureAgentBuiltIns(String project){try{
        var existingByName=new java.util.HashMap<String,ProductivityStore.AgentProfile>();
        productivity.agentProfiles(project).forEach(profile->existingByName.put(profile.name(), profile));
        for(var seed:AGENT_SEEDS){
            var existing=existingByName.get(seed.name());
            if(existing!=null){
                if(blank(existing.templateKey())) {
                    productivity.saveAgentProfile(existing.id(), existing.projectKey(), existing.name(),
                            existing.description(), existing.systemPrompt(), existing.modelProfileId(),
                            withPlanToolsJson(existing.toolNamesJson()), existing.skillNamesJson(), existing.outputSchema(),
                            existing.collaborationRole(), existing.handoffPolicy(), existing.workspaceScope(),
                            existing.approvalPolicy(), existing.thinkingMode(), existing.reasoningEffort(),
                            existing.executionShell(), existing.enabled(), seed.key(), seed.version());
                }
                continue;
            }
            productivity.saveAgentProfile(null,project,seed.name(),seed.description(),seed.prompt(),null,
                    mapper.writeValueAsString(withPlanTools(seed.tools())),mapper.writeValueAsString(List.of()),
                    seed.outputSchema(),seed.role(),seed.handoff(),"PROJECT",seed.approval(),true,
                    seed.key(),seed.version());
        }
    }catch(Exception ignored){}}
    private void ensureBuiltIns(String project){if(!productivity.templates(project).isEmpty())return;try{
        productivity.saveTemplate(null,project,"代码审查","/review","审查 ${repository} 的代码，按 ${outputFormat} 输出风险、证据和修复建议。","{\"repository\":\"当前仓库\",\"outputFormat\":\"Markdown\"}","可选代码或补丁附件","read_file,list_dir,search",null);
        productivity.saveTemplate(null,project,"内容总结","/summarize","总结以下目标或资料，输出 ${outputFormat}，重点保留决策、风险和待办。","{\"outputFormat\":\"Markdown\"}","可选文档附件","read_file,search_knowledge",null);
        productivity.saveTemplate(null,project,"深度研究","/research","研究 ${topic}，覆盖来源、对比、结论和不确定性，输出 ${outputFormat}。","{\"topic\":\"待填写\",\"outputFormat\":\"Markdown\"}","可选参考资料","web_search,web_fetch,github_repo_fetch,search_knowledge",null);
    }catch(Exception ignored){}}
    private Map<String,String> readMap(String json){try{return new LinkedHashMap<>(mapper.readValue(json,STRING_MAP));}catch(Exception e){return new LinkedHashMap<>();}}
    private static String render(String prompt,Map<String,String> vars){Matcher m=VARIABLE.matcher(prompt);StringBuffer out=new StringBuffer();List<String> missing=new ArrayList<>();while(m.find()){String value=vars.get(m.group(1));if(value==null||value.isBlank()){missing.add(m.group(1));value=m.group();}m.appendReplacement(out,Matcher.quoteReplacement(value));}m.appendTail(out);if(!missing.isEmpty())throw new IllegalArgumentException("missing template variables: "+String.join(", ",missing));return out.toString();}
    private static Instant firstScheduleRun(String type,String value,String requested){
        if(!Set.of("ONCE","DAILY","WEEKLY","CRON").contains(type))
            throw new IllegalArgumentException("scheduleType must be ONCE, DAILY, WEEKLY, or CRON");
        if("CRON".equals(type)){
            CronExpression cron=CronExpression.parse(value==null?"":value.trim());
            if(requested!=null&&!requested.isBlank())return Instant.parse(requested);
            ZonedDateTime next=cron.next(ZonedDateTime.now(ZoneId.systemDefault()));
            if(next==null)throw new IllegalArgumentException("cron expression has no next execution");
            return next.toInstant();
        }
        if(requested==null||requested.isBlank())throw new IllegalArgumentException("nextRunAt is required for "+type);
        Instant next=Instant.parse(requested);
        if(next.isBefore(Instant.now()))throw new IllegalArgumentException("nextRunAt must be in the future");
        return next;
    }
    private static long value(Long v){return v==null?0:v;}private static double value(Double v){return v==null?0:v;}
    private static AgentSeed findAgentSeed(String key,String name){return AGENT_SEEDS.stream()
            .filter(seed->(!blank(key)&&seed.key().equals(key))||seed.name().equals(name)).findFirst().orElse(null);}
    private static List<String> defaultToolsForRole(String role){return switch(role){
        case "LEADER"->List.of("list_agent_profiles","spawn_agent","list_agents","get_agent_result","cancel_agent","read_file","list_dir","search_knowledge","web_search","web_fetch","github_repo_fetch","mcp__github__*");
        case "REVIEWER"->List.of("list_dir","read_file","search_knowledge","session_search");
        case "RUNNER"->List.of("list_dir","read_file","execute_command","search_knowledge");
        default->List.of("list_dir","read_file","write_file","search_knowledge");
    };}
    private static List<String> sanitizeToolsForRole(String role,List<String> tools){
        var cleaned=tools.stream().filter(v->v!=null&&!v.isBlank()).map(String::trim).distinct().limit(50).toList();
        if("LEADER".equals(role))return cleaned;
        return cleaned.stream().filter(v->!Set.of("list_agent_profiles","spawn_agent","list_agents","get_agent_result","cancel_agent").contains(v)).toList();
    }
    private static List<String> withPlanTools(List<String> tools) {
        var values = new java.util.LinkedHashSet<>(tools);
        values.addAll(PlanToolProvider.PROFILE_PLAN_TOOLS);
        values.addAll(CollaborationToolProvider.PROFILE_COLLABORATION_TOOLS);
        return List.copyOf(values);
    }
    private String withPlanToolsJson(String json) throws Exception {
        List<String> values = json == null || json.isBlank() ? List.of() : mapper.readValue(json, STRING_LIST);
        return mapper.writeValueAsString(withPlanTools(values));
    }
    private static String defaultApprovalForRole(String role){return "REVIEWER".equals(role)?"READ_ONLY":"INHERIT";}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ResponseStatusException notFound(String name){return new ResponseStatusException(HttpStatus.NOT_FOUND,name+" not found");}
    private static final List<AgentSeed> AGENT_SEEDS=List.of(
            new AgentSeed("leader","Leader 任务队长",1,"把一句话目标拆成可验证计划，挑选专家并综合最终交付。",
                    "你是 PaiCLI 的 Leader 智能体。先理解用户目标，调用 list_agent_profiles 查看可用专家，再用 spawn_agent 按 agent_profile_id 分派独立、可验证的子任务。独立节点可并行；审查、测试等后置节点必须用 dependencies 引用前置 delegation_id 或 child_run_id，并声明资源读写集与失败策略。持续用 list_agents 和 get_agent_result 跟踪结果，最后合并为一个完整交付。不要重复派发任务。",
                    "LEADER","LEADER_ASSIGNED","INHERIT",
                    List.of("list_agent_profiles","spawn_agent","list_agents","get_agent_result","cancel_agent","read_file","list_dir","search_knowledge","web_search","web_fetch","github_repo_fetch","mcp__github__*"),
                    "输出 Markdown：目标拆解、专家分工、关键结果、风险、最终建议或交付物。"),
            new AgentSeed("requirements","需求分析专家",1,"澄清目标、边界、用户场景和验收标准。",
                    "你是需求分析专家。将模糊目标转成清晰任务说明，识别范围、约束、用户路径、边界条件和验收标准。只在必要时提出阻塞问题；否则给出可执行的需求拆解。",
                    "EXPERT","LEADER_ASSIGNED","READ_ONLY",
                    List.of("read_file","list_dir","search_knowledge","session_search"),
                    "输出：需求摘要、范围、假设、验收标准、未决问题。"),
            new AgentSeed("implementation","代码实现专家",1,"在受控工作区内实现功能并说明关键改动。",
                    "你是代码实现专家。优先遵循现有架构和代码风格，做最小可交付改动。修改前先定位相关文件，修改后说明行为变化和可能影响。涉及危险操作时等待审批。",
                    "EXPERT","LEADER_ASSIGNED","INHERIT",
                    List.of("list_dir","read_file","write_file","execute_command","search_knowledge"),
                    "输出：修改摘要、关键文件、验证命令、风险。"),
            new AgentSeed("runner","测试验证专家",1,"设计并执行回归验证，定位失败原因。",
                    "你是测试验证专家。根据任务目标选择最小但有效的测试范围，运行验证命令，记录失败复现、日志要点和建议修复路径。不做无关重构。",
                    "RUNNER","LEADER_ASSIGNED","INHERIT",
                    List.of("list_dir","read_file","execute_command","search_knowledge"),
                    "输出：验证范围、命令、结果、失败分析、剩余风险。"),
            new AgentSeed("reviewer","代码审查专家",1,"审查缺陷、回归风险和缺失测试。",
                    "你是代码审查专家。以缺陷优先，检查行为回归、安全边界、数据兼容、并发和测试缺口。发现问题时给出文件位置、影响和建议修复；没有问题也要说明剩余风险。",
                    "REVIEWER","LEADER_ASSIGNED","READ_ONLY",
                    List.of("list_dir","read_file","search_knowledge","session_search"),
                    "输出：按严重程度排序的问题、证据、建议、测试缺口。"),
            new AgentSeed("docs","文档交付专家",1,"把实现结果整理成用户可读文档和变更说明。",
                    "你是文档交付专家。根据代码和任务结果更新 README、接口说明、变更日志或交付摘要。内容要准确、可审计，避免夸大未实现能力。",
                    "EXPERT","LEADER_ASSIGNED","INHERIT",
                    List.of("read_file","write_file","search_knowledge"),
                    "输出：文档改动、用户可见能力、限制、后续建议。")
    );
    private record AgentSeed(String key,String name,int version,String description,String prompt,String role,String handoff,
                             String approval,List<String> tools,String outputSchema){}
    private record ModelSeed(String name, String baseUrl, String apiKeyEnv, String model,
                             int maxContextTokens, int maxOutputTokens) { }
}
