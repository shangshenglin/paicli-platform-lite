package com.paicli.platform.server.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/v1/productivity")
public class ProductivityController {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_.-]{0,79})}");
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
    public void deleteTemplate(@PathVariable String id) { if (!productivity.deleteTemplate(id)) notFound("template"); }

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
    public void deleteProfile(@PathVariable String id) { if (!productivity.deleteModelProfile(id)) notFound("profile"); }

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
    public Map<String,Object> batch(@Valid @RequestBody ApiDtos.QueueBatchRequest request){
        List<String> changed=new ArrayList<>();String action=request.action().trim().toUpperCase();
        for(String runId:request.runIds().stream().filter(v->v!=null&&!v.isBlank()).distinct().limit(100).toList()){
            boolean ok=switch(action){
                case "CANCEL"->{boolean canceled=runtime.cancelRunTree(runId).contains(runId);modelClient.cancel(runId);tools.release(runId);yield canceled;}
                case "REQUEUE"->{boolean requeued=productivity.requeue(runId);if(requeued)runtime.appendEvent(runId,"run.requeued","{\"source\":\"productivity-console\"}");yield requeued;}
                case "PRIORITY"->productivity.setPriority(runId,request.priority()==null?0:request.priority());
                default->throw new IllegalArgumentException("action must be CANCEL, REQUEUE, or PRIORITY");};
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
    public void deleteSchedule(@PathVariable String id){if(!productivity.deleteSchedule(id))notFound("schedule");}

    @GetMapping("/notifications")
    public List<ProductivityStore.NotificationChannel> notifications(@RequestParam(defaultValue="default")String projectKey){return productivity.notificationChannels(projectKey);}
    @PostMapping("/notifications") @ResponseStatus(HttpStatus.CREATED)
    public ProductivityStore.NotificationChannel createNotification(@Valid @RequestBody ApiDtos.NotificationChannelRequest r){return saveNotification(null,r);}
    @PutMapping("/notifications/{id}")
    public ProductivityStore.NotificationChannel updateNotification(@PathVariable String id,@Valid @RequestBody ApiDtos.NotificationChannelRequest r){return saveNotification(id,r);}
    @DeleteMapping("/notifications/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotification(@PathVariable String id){if(!productivity.deleteNotification(id))notFound("notification");}

    private ProductivityStore.TaskTemplate saveTemplate(String id,ApiDtos.TaskTemplateRequest r){
        try{return productivity.saveTemplate(id,r.projectKey(),r.name(),r.shortcut(),r.prompt(),mapper.writeValueAsString(r.variables()==null?Map.of():r.variables()),r.attachmentRequirements(),String.join(",",r.allowedTools()==null?List.of():r.allowedTools()),r.modelProfileId());}
        catch(Exception e){throw e instanceof RuntimeException runtime?runtime:new IllegalArgumentException("invalid template",e);}
    }
    private ProductivityStore.ModelProfile saveProfile(String id,ApiDtos.ModelProfileRequest r){return productivity.saveModelProfile(id,r.projectKey(),r.name(),r.baseUrl(),r.apiKeyEnv(),r.model(),r.fallbackModel(),r.maxContextTokens()==null?128000:r.maxContextTokens(),r.maxOutputTokens()==null?4096:r.maxOutputTokens(),r.inputPrice()==null?0:r.inputPrice(),r.outputPrice()==null?0:r.outputPrice(),Boolean.TRUE.equals(r.localModel()),Boolean.TRUE.equals(r.makeDefault()));}
    private ProductivityStore.ScheduledTask saveSchedule(String id,ApiDtos.ScheduledTaskRequest r){
        try{return productivity.saveSchedule(id,r.projectKey(),r.name(),r.templateId(),r.scheduleType(),r.scheduleValue(),mapper.writeValueAsString(r.variables()==null?Map.of():r.variables()),r.enabled()==null||r.enabled(),r.nextRunAt()==null||r.nextRunAt().isBlank()?Instant.now():Instant.parse(r.nextRunAt()));}
        catch(Exception e){throw e instanceof RuntimeException runtime?runtime:new IllegalArgumentException("invalid schedule",e);}
    }
    private ProductivityStore.NotificationChannel saveNotification(String id,ApiDtos.NotificationChannelRequest r){return productivity.saveNotification(id,r.projectKey(),r.name(),r.type(),r.endpoint(),r.secretEnv(),String.join(",",r.events()),r.enabled()==null||r.enabled());}
    private void ensureBuiltIns(String project){if(!productivity.templates(project).isEmpty())return;try{
        productivity.saveTemplate(null,project,"代码审查","/review","审查 ${repository} 的代码，按 ${outputFormat} 输出风险、证据和修复建议。","{\"repository\":\"当前仓库\",\"outputFormat\":\"Markdown\"}","可选代码或补丁附件","read_file,list_dir,search",null);
        productivity.saveTemplate(null,project,"内容总结","/summarize","总结以下目标或资料，输出 ${outputFormat}，重点保留决策、风险和待办。","{\"outputFormat\":\"Markdown\"}","可选文档附件","read_file,search_knowledge",null);
        productivity.saveTemplate(null,project,"深度研究","/research","研究 ${topic}，覆盖来源、对比、结论和不确定性，输出 ${outputFormat}。","{\"topic\":\"待填写\",\"outputFormat\":\"Markdown\"}","可选参考资料","web_search,search_knowledge",null);
    }catch(Exception ignored){}}
    private Map<String,String> readMap(String json){try{return new LinkedHashMap<>(mapper.readValue(json,STRING_MAP));}catch(Exception e){return new LinkedHashMap<>();}}
    private static String render(String prompt,Map<String,String> vars){Matcher m=VARIABLE.matcher(prompt);StringBuffer out=new StringBuffer();List<String> missing=new ArrayList<>();while(m.find()){String value=vars.get(m.group(1));if(value==null||value.isBlank()){missing.add(m.group(1));value=m.group();}m.appendReplacement(out,Matcher.quoteReplacement(value));}m.appendTail(out);if(!missing.isEmpty())throw new IllegalArgumentException("missing template variables: "+String.join(", ",missing));return out.toString();}
    private static long value(Long v){return v==null?0:v;}private static double value(Double v){return v==null?0:v;}
    private static ResponseStatusException notFound(String name){return new ResponseStatusException(HttpStatus.NOT_FOUND,name+" not found");}
}
