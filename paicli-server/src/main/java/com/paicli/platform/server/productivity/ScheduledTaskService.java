package com.paicli.platform.server.productivity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
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
    private final ObjectMapper mapper;

    public ScheduledTaskService(ProductivityStore productivity,SqliteRuntimeStore runtime,ObjectMapper mapper){
        this.productivity=productivity;this.runtime=runtime;this.mapper=mapper;
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
                var run=runtime.createRun(session.id(),prompt,"auto","",java.util.List.of(),template.modelProfileId(),0,0);
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
    private static Instant next(ProductivityStore.ScheduledTask task){
        Instant now=Instant.now();return switch(task.scheduleType()){
            case "DAILY"->now.plus(1,ChronoUnit.DAYS);
            case "WEEKLY"->now.plus(7,ChronoUnit.DAYS);
            case "CRON"->{ZonedDateTime value=CronExpression.parse(task.scheduleValue()).next(ZonedDateTime.now(ZoneOffset.UTC));yield value==null?null:value.toInstant();}
            default->null;};
    }
    private Map<String,String> read(String json){try{return new LinkedHashMap<>(mapper.readValue(json,MAP));}catch(Exception e){return new LinkedHashMap<>();}}
    private static String render(String prompt,Map<String,String> variables){Matcher matcher=VARIABLE.matcher(prompt);StringBuffer out=new StringBuffer();while(matcher.find()){String value=variables.get(matcher.group(1));if(value==null||value.isBlank())throw new IllegalArgumentException("missing template variable: "+matcher.group(1));matcher.appendReplacement(out,Matcher.quoteReplacement(value));}matcher.appendTail(out);return out.toString();}
}
