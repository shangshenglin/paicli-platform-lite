package com.paicli.platform.server.productivity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.plan.PlanService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScheduledTaskService {
    private static final Pattern VARIABLE=Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_.-]{0,79})}");
    private static final TypeReference<Map<String,String>> MAP=new TypeReference<>(){};
    private final ProductivityStore productivity;
    private final SqliteRuntimeStore runtime;
    private final PlanService plans;
    private final ObjectMapper mapper;

    public ScheduledTaskService(ProductivityStore productivity,SqliteRuntimeStore runtime,PlanService plans,ObjectMapper mapper){
        this.productivity=productivity;this.runtime=runtime;this.plans=plans;this.mapper=mapper;
    }

    @Scheduled(fixedDelayString="${paicli.productivity.scheduler-delay-ms:15000}")
    public void runDueTasks(){
        for(var task:productivity.dueSchedules()){
            if(!productivity.claimSchedule(task.id()))continue;
            try{
                var template=productivity.findTemplate(task.projectKey(),task.templateId()).orElseThrow();
                Map<String,String> variables=read(template.variablesJson());variables.putAll(read(task.variablesJson()));
                String prompt=render(template.prompt(),variables);
                ensureBudget(task.projectKey());
                var session=runtime.createSession("[定时] "+task.name(),task.projectKey(),null);
                var team=resolveTeam(task);
                var agent=team == null ? resolveAgent(task) : productivity.resolveAgentProfile(task.projectKey(),
                        team.leaderAgentProfileId()).orElseThrow();
                String modelProfileId=resolveModelProfile(task,template,agent);
                String runInput=team == null ? prompt : teamLeaderInput(prompt,team.name());
                var run=runtime.createRun(session.id(),runInput,"auto","",java.util.List.of(),modelProfileId,
                        agent == null ? null : agent.id(),0,0,
                        agent == null ? "bash" : agent.executionShell());
                if(team != null){
                    runtime.saveCollaborationPolicy(run.id(),true,"MEDIUM","MEDIUM",
                            team.memberAgentProfileIdsJson(),team.maxExperts(),team.maxDepth(),team.maxExperts(),
                            team.maxConcurrency(),0,0,false,team.requireReviewer(),team.requireRunner());
                    plans.createAutomaticCollaborationPlan(session.id(),run.id(),task.projectKey(),prompt);
                }
                productivity.markTemplateUsed(task.projectKey(),template.id());
                productivity.completeSchedule(task.id(),run.id(),next(task));
            }catch(Exception e){
                productivity.retrySchedule(task.id(),Instant.now().plus(5,ChronoUnit.MINUTES));
            }
        }
    }

    private void ensureBudget(String project){
        var budget=productivity.budget(project);var day=productivity.usage(project,1);var month=productivity.usage(project,31);
        if((budget.dailyTokens()>0&&day.inputTokens()+day.outputTokens()>=budget.dailyTokens())
                ||(budget.monthlyTokens()>0&&month.inputTokens()+month.outputTokens()>=budget.monthlyTokens())
                ||(budget.dailyCost()>0&&day.estimatedCost()>=budget.dailyCost())
                ||(budget.monthlyCost()>0&&month.estimatedCost()>=budget.monthlyCost()))
            throw new IllegalStateException("project model budget exceeded");
    }
    private ProductivityStore.AgentProfile resolveAgent(ProductivityStore.ScheduledTask task){
        if(task.agentProfileId()==null || task.agentProfileId().isBlank()) return null;
        return productivity.resolveAgentProfile(task.projectKey(),task.agentProfileId())
                .orElseThrow(()->new IllegalStateException("scheduled agent profile is unavailable"));
    }
    private ProductivityStore.AgentTeam resolveTeam(ProductivityStore.ScheduledTask task){
        if(task.agentTeamId()==null || task.agentTeamId().isBlank()) return null;
        var team=productivity.findAgentTeam(task.agentTeamId()).filter(value -> value.enabled()
                && value.projectKey().equals(task.projectKey()))
                .orElseThrow(()->new IllegalStateException("scheduled agent team is unavailable"));
        var leader=productivity.resolveAgentProfile(task.projectKey(),team.leaderAgentProfileId())
                .orElseThrow(()->new IllegalStateException("scheduled agent team leader is unavailable"));
        if(!"LEADER".equalsIgnoreCase(leader.collaborationRole())) {
            throw new IllegalStateException("scheduled agent team leader must be a LEADER profile");
        }
        return team;
    }
    private String resolveModelProfile(ProductivityStore.ScheduledTask task,ProductivityStore.TaskTemplate template,
                                       ProductivityStore.AgentProfile agent){
        String candidate=task.modelProfileId();
        if((candidate==null || candidate.isBlank()) && agent != null) candidate=agent.modelProfileId();
        if(candidate==null || candidate.isBlank()) candidate=template.modelProfileId();
        if(candidate==null || candidate.isBlank()) return null;
        return productivity.resolveModelProfile(task.projectKey(),candidate)
                .orElseThrow(()->new IllegalStateException("scheduled model profile is unavailable")).id();
    }
    private static String teamLeaderInput(String prompt,String teamName){
        return "Scheduled team task. You are the Leader of '"+teamName+"'. Follow the persisted team policy, delegate only necessary work to allowed experts, then synthesize conclusions, risks, and next actions.\n\nTask:\n"+prompt;
    }
    private static Instant next(ProductivityStore.ScheduledTask task){
        Instant now=Instant.now();return switch(task.scheduleType()){
            case "DAILY"->now.plus(1,ChronoUnit.DAYS);
            case "WEEKLY"->now.plus(7,ChronoUnit.DAYS);
            case "CRON"->{ZonedDateTime value=CronExpression.parse(task.scheduleValue()).next(ZonedDateTime.now(ZoneId.systemDefault()));yield value==null?null:value.toInstant();}
            default->null;};
    }
    private Map<String,String> read(String json){try{return new LinkedHashMap<>(mapper.readValue(json,MAP));}catch(Exception e){return new LinkedHashMap<>();}}
    private static String render(String prompt,Map<String,String> variables){Matcher matcher=VARIABLE.matcher(prompt);StringBuffer out=new StringBuffer();while(matcher.find()){String value=variables.get(matcher.group(1));if(value==null||value.isBlank())throw new IllegalArgumentException("missing template variable: "+matcher.group(1));matcher.appendReplacement(out,Matcher.quoteReplacement(value));}matcher.appendTail(out);return out.toString();}
}
