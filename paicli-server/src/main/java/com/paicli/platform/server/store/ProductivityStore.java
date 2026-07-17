package com.paicli.platform.server.store;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.model.ModelRoute;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProductivityStore {
    private final SqliteConnectionFactory connections;

    public ProductivityStore(PlatformProperties properties) {
        this.connections = new SqliteConnectionFactory(
                properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize());
    }

    public List<TaskTemplate> templates(String projectKey) {
        List<TaskTemplate> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM task_templates WHERE project_key=? ORDER BY use_count DESC,updated_at DESC")) {
            ps.setString(1, project(projectKey));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(template(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list task templates", e); }
    }

    public Optional<TaskTemplate> findTemplate(String projectKey, String idOrShortcut) {
        String reference = idOrShortcut == null ? "" : idOrShortcut.trim();
        String shortcut = reference.startsWith("/") ? reference.toLowerCase() : "/" + reference.toLowerCase();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM task_templates WHERE project_key=? AND (id=? OR shortcut=?)")) {
            ps.setString(1, project(projectKey)); ps.setString(2, reference); ps.setString(3, shortcut);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(template(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("find task template", e); }
    }

    public TaskTemplate saveTemplate(String id, String projectKey, String name, String shortcut,
                                     String prompt, String variablesJson, String attachmentRequirements,
                                     String allowedTools, String modelProfileId) {
        String key = project(projectKey); String resolvedId = blank(id) ? id("template") : id.trim();
        String resolvedShortcut = blank(shortcut) ? "" : shortcut.trim().toLowerCase();
        if (!resolvedShortcut.isEmpty() && !resolvedShortcut.matches("/[a-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("shortcut must look like /review");
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_templates(id,project_key,name,shortcut,prompt,variables_json," +
                        "attachment_requirements,allowed_tools,model_profile_id,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name," +
                        "shortcut=excluded.shortcut,prompt=excluded.prompt,variables_json=excluded.variables_json," +
                        "attachment_requirements=excluded.attachment_requirements,allowed_tools=excluded.allowed_tools," +
                        "model_profile_id=excluded.model_profile_id,updated_at=excluded.updated_at")) {
            ps.setString(1, resolvedId); ps.setString(2, key); ps.setString(3, text(name, "name", 120));
            ps.setString(4, resolvedShortcut); ps.setString(5, text(prompt, "prompt", 64_000));
            ps.setString(6, json(variablesJson)); ps.setString(7, value(attachmentRequirements, 2_000));
            ps.setString(8, value(allowedTools, 4_000)); ps.setString(9, nullable(modelProfileId));
            ps.setString(10, now.toString()); ps.setString(11, now.toString()); ps.executeUpdate();
            return findTemplate(key, resolvedId).orElseThrow();
        } catch (SQLException e) { throw failure("save task template", e); }
    }

    public TaskTemplate markTemplateUsed(String projectKey, String idOrShortcut) {
        TaskTemplate found = findTemplate(projectKey, idOrShortcut)
                .orElseThrow(() -> new IllegalArgumentException("task template not found"));
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE task_templates SET use_count=use_count+1,last_used_at=?,updated_at=? WHERE id=?")) {
            String now = Instant.now().toString(); ps.setString(1, now); ps.setString(2, now); ps.setString(3, found.id());
            ps.executeUpdate(); return findTemplate(projectKey, found.id()).orElseThrow();
        } catch (SQLException e) { throw failure("mark task template used", e); }
    }

    public boolean deleteTemplate(String id) { return delete("task_templates", id); }

    public List<ModelProfile> modelProfiles(String projectKey) {
        List<ModelProfile> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM model_profiles WHERE project_key=? ORDER BY is_default DESC,name COLLATE NOCASE")) {
            ps.setString(1, project(projectKey));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(profile(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list model profiles", e); }
    }

    public ModelProfile saveModelProfile(String id, String projectKey, String name, String baseUrl,
                                         String apiKeyEnv, String model, String fallbackModel,
                                         int maxContextTokens, int maxOutputTokens, double inputPrice,
                                         double outputPrice, boolean localModel, boolean makeDefault) {
        String key = project(projectKey); String resolvedId = blank(id) ? id("profile") : id.trim(); Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            if (makeDefault) try (PreparedStatement clear = c.prepareStatement(
                    "UPDATE model_profiles SET is_default=0 WHERE project_key=?")) { clear.setString(1, key); clear.executeUpdate(); }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO model_profiles(id,project_key,name,base_url,api_key_env,model,fallback_model," +
                            "max_context_tokens,max_output_tokens,input_price,output_price,local_model,is_default,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name," +
                            "base_url=excluded.base_url,api_key_env=excluded.api_key_env,model=excluded.model," +
                            "fallback_model=excluded.fallback_model,max_context_tokens=excluded.max_context_tokens," +
                            "max_output_tokens=excluded.max_output_tokens,input_price=excluded.input_price," +
                            "output_price=excluded.output_price,local_model=excluded.local_model," +
                            "is_default=excluded.is_default,updated_at=excluded.updated_at")) {
                int i=1; ps.setString(i++, resolvedId); ps.setString(i++, key); ps.setString(i++, text(name,"name",120));
                ps.setString(i++, text(baseUrl,"baseUrl",500)); ps.setString(i++, env(apiKeyEnv));
                ps.setString(i++, text(model,"model",160)); ps.setString(i++, value(fallbackModel,160));
                ps.setInt(i++, Math.max(1_024,maxContextTokens)); ps.setInt(i++, Math.max(256,maxOutputTokens));
                ps.setDouble(i++, Math.max(0,inputPrice)); ps.setDouble(i++, Math.max(0,outputPrice));
                ps.setInt(i++, localModel?1:0); ps.setInt(i++, makeDefault?1:0);
                ps.setString(i++, now.toString()); ps.setString(i, now.toString()); ps.executeUpdate();
            }
            c.commit(); return findModelProfile(resolvedId).orElseThrow();
        } catch (SQLException e) { throw failure("save model profile", e); }
    }

    public Optional<ModelProfile> findModelProfile(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c=open(); PreparedStatement ps=c.prepareStatement("SELECT * FROM model_profiles WHERE id=?")) {
            ps.setString(1,id); try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(profile(rs)):Optional.empty();}
        } catch(SQLException e){throw failure("find model profile",e);}
    }

    public Optional<ModelProfile> resolveModelProfile(String projectKey, String requestedId) {
        if (!blank(requestedId)) return findModelProfile(requestedId).filter(p -> p.projectKey().equals(project(projectKey)));
        return modelProfiles(projectKey).stream().filter(ModelProfile::defaultProfile).findFirst();
    }

    public ModelRoute route(ModelProfile profile) {
        String key = profile.apiKeyEnv().isBlank() ? "" : System.getenv(profile.apiKeyEnv());
        return new ModelRoute(profile.id(), profile.name(), profile.baseUrl(), key == null ? "" : key,
                profile.model(), profile.fallbackModel(), profile.maxContextTokens(),
                profile.maxOutputTokens(), profile.localModel());
    }

    public boolean deleteModelProfile(String id) { return delete("model_profiles", id); }

    public BudgetPolicy saveBudget(String projectKey, long dailyTokens, long monthlyTokens,
                                   double dailyCost, double monthlyCost, double warnRatio, int maxConcurrentRuns) {
        String key=project(projectKey); Instant now=Instant.now();
        try(Connection c=open(); PreparedStatement ps=c.prepareStatement(
                "INSERT INTO budget_policies(project_key,daily_tokens,monthly_tokens,daily_cost,monthly_cost,warn_ratio,max_concurrent_runs,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(project_key) DO UPDATE SET daily_tokens=excluded.daily_tokens," +
                        "monthly_tokens=excluded.monthly_tokens,daily_cost=excluded.daily_cost,monthly_cost=excluded.monthly_cost," +
                        "warn_ratio=excluded.warn_ratio,max_concurrent_runs=excluded.max_concurrent_runs,updated_at=excluded.updated_at")){
            ps.setString(1,key);ps.setLong(2,Math.max(0,dailyTokens));ps.setLong(3,Math.max(0,monthlyTokens));
            ps.setDouble(4,Math.max(0,dailyCost));ps.setDouble(5,Math.max(0,monthlyCost));
            ps.setDouble(6,Math.max(.5,Math.min(warnRatio,.99)));ps.setInt(7,Math.max(1,Math.min(maxConcurrentRuns,32)));
            ps.setString(8,now.toString());ps.executeUpdate();return budget(key);
        }catch(SQLException e){throw failure("save budget",e);}
    }

    public BudgetPolicy budget(String projectKey) {
        String key=project(projectKey);
        try(Connection c=open();PreparedStatement ps=c.prepareStatement("SELECT * FROM budget_policies WHERE project_key=?")){
            ps.setString(1,key);try(ResultSet rs=ps.executeQuery()){
                if(rs.next()) return new BudgetPolicy(key,rs.getLong("daily_tokens"),rs.getLong("monthly_tokens"),
                        rs.getDouble("daily_cost"),rs.getDouble("monthly_cost"),rs.getDouble("warn_ratio"),
                        rs.getInt("max_concurrent_runs"),Instant.parse(rs.getString("updated_at")));
            }
            return new BudgetPolicy(key,0,0,0,0,.8,4,null);
        }catch(SQLException e){throw failure("read budget",e);}
    }

    public boolean reserveModelBudget(String projectKey, String reservationKey,
                                      long requestedTokens, double requestedCost) {
        BudgetPolicy policy = budget(projectKey);
        if (policy.dailyTokens() <= 0 && policy.monthlyTokens() <= 0
                && policy.dailyCost() <= 0 && policy.monthlyCost() <= 0) return true;
        String key = project(projectKey);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT OR IGNORE INTO budget_reservations(" +
                                "reservation_key,project_key,reserved_tokens,reserved_cost,created_at) VALUES(?,?,?,?,?)")) {
                    insert.setString(1, reservationKey); insert.setString(2, key);
                    insert.setLong(3, Math.max(0, requestedTokens));
                    insert.setDouble(4, Math.max(0, requestedCost));
                    insert.setString(5, Instant.now().toString()); insert.executeUpdate();
                }
                long reservedTokens; double reservedCost;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(SUM(reserved_tokens),0),COALESCE(SUM(reserved_cost),0) " +
                                "FROM budget_reservations WHERE project_key=?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); reservedTokens = rs.getLong(1); reservedCost = rs.getDouble(2); }
                }
                UsageSummary daily = usage(key, 1); UsageSummary monthly = usage(key, 31);
                boolean exceeded = (policy.dailyTokens() > 0
                        && daily.inputTokens() + daily.outputTokens() + reservedTokens > policy.dailyTokens())
                        || (policy.monthlyTokens() > 0
                        && monthly.inputTokens() + monthly.outputTokens() + reservedTokens > policy.monthlyTokens())
                        || (policy.dailyCost() > 0 && daily.estimatedCost() + reservedCost > policy.dailyCost())
                        || (policy.monthlyCost() > 0 && monthly.estimatedCost() + reservedCost > policy.monthlyCost());
                if (exceeded) { c.rollback(); return false; }
                c.commit(); return true;
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) { }
                throw e;
            }
        } catch (SQLException e) { throw failure("reserve model budget", e); }
    }

    public void releaseModelBudget(String reservationKey) {
        if (reservationKey == null || reservationKey.isBlank()) return;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM budget_reservations WHERE reservation_key=?")) {
            ps.setString(1, reservationKey); ps.executeUpdate();
        } catch (SQLException e) { throw failure("release model budget", e); }
    }

    public UsageSummary usage(String projectKey, int days) {
        String key=project(projectKey); Instant since=Instant.now().minus(Math.max(1,Math.min(days,366)), ChronoUnit.DAYS);
        String sql="SELECT COUNT(u.id) calls,COUNT(DISTINCT r.id) runs,"+
                "COALESCE(SUM(CASE WHEN u.input_tokens>0 THEN u.input_tokens ELSE u.estimated_input_tokens END),0) input_tokens,"+
                "COALESCE(SUM(u.output_tokens),0) output_tokens,COALESCE(SUM(u.cached_input_tokens),0) cached_tokens,"+
                "COALESCE(AVG(u.duration_ms),0) avg_duration,COALESCE(SUM(u.retry_count),0) retries,"+
                "COUNT(DISTINCT CASE WHEN r.status='FAILED' THEN r.id END) failed,"+
                "COALESCE(SUM(CASE WHEN COALESCE(u.local_model,0)=1 THEN 0 ELSE " +
                "((CASE WHEN u.input_tokens>0 THEN u.input_tokens ELSE u.estimated_input_tokens END)/1000000.0*COALESCE(p.input_price,0)+"+
                "u.output_tokens/1000000.0*COALESCE(p.output_price,0)) END),0) cost " +
                "FROM runs r JOIN sessions s ON s.id=r.session_id LEFT JOIN model_usage u ON u.run_id=r.id " +
                "LEFT JOIN model_profiles p ON p.id=r.model_profile_id " +
                "WHERE s.project_key=? AND r.created_at>=?";
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,key);ps.setString(2,since.toString());try(ResultSet rs=ps.executeQuery()){
                rs.next();long calls=rs.getLong("calls"),runs=rs.getLong("runs"),failed=rs.getLong("failed");
                return new UsageSummary(key,days,calls,rs.getLong("input_tokens"),rs.getLong("output_tokens"),
                        rs.getLong("cached_tokens"),rs.getLong("avg_duration"),runs==0?0:(double)failed/runs,
                        rs.getLong("retries"),rs.getDouble("cost"),budget(key),usageBreakdown(key,since));
            }
        }catch(SQLException e){throw failure("read usage summary",e);}
    }

    private List<UsageBreakdown> usageBreakdown(String projectKey,Instant since){
        List<UsageBreakdown> values=new ArrayList<>();
        String sql="SELECT substr(r.created_at,1,10) usage_day,r.session_id,s.title session_title,"+
                "COALESCE(NULLIF(u.model_name,''),'未记录') model_name,COUNT(u.id) calls,"+
                "COALESCE(SUM(CASE WHEN u.input_tokens>0 THEN u.input_tokens ELSE u.estimated_input_tokens END),0) input_tokens,"+
                "COALESCE(SUM(u.output_tokens),0) output_tokens,COALESCE(SUM(u.cached_input_tokens),0) cached_tokens,"+
                "COALESCE(AVG(u.duration_ms),0) avg_duration,COALESCE(SUM(u.retry_count),0) retries,"+
                "MAX(COALESCE(u.local_model,0)) local_model,"+
                "COALESCE(SUM(CASE WHEN COALESCE(u.local_model,0)=1 THEN 0 ELSE "+
                "((CASE WHEN u.input_tokens>0 THEN u.input_tokens ELSE u.estimated_input_tokens END)/1000000.0*COALESCE(p.input_price,0)+"+
                "u.output_tokens/1000000.0*COALESCE(p.output_price,0)) END),0) cost "+
                "FROM runs r JOIN sessions s ON s.id=r.session_id LEFT JOIN model_usage u ON u.run_id=r.id "+
                "LEFT JOIN model_profiles p ON p.id=r.model_profile_id WHERE s.project_key=? AND r.created_at>=? "+
                "GROUP BY usage_day,r.session_id,s.title,model_name ORDER BY usage_day DESC,calls DESC LIMIT 200";
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,projectKey);ps.setString(2,since.toString());
            try(ResultSet rs=ps.executeQuery()){while(rs.next())values.add(new UsageBreakdown(rs.getString("usage_day"),
                    rs.getString("session_id"),rs.getString("session_title"),rs.getString("model_name"),
                    rs.getLong("calls"),rs.getLong("input_tokens"),rs.getLong("output_tokens"),
                    rs.getLong("cached_tokens"),rs.getLong("avg_duration"),rs.getLong("retries"),
                    rs.getDouble("cost"),rs.getInt("local_model")!=0));}
            return values;
        }catch(SQLException e){throw failure("read usage breakdown",e);}
    }

    public List<QueueItem> queue(String projectKey) {
        List<QueueItem> values=new ArrayList<>();String key=project(projectKey);
        BudgetPolicy policy=budget(key);UsageSummary monthly=usage(key,31);
        long remaining=policy.monthlyTokens()>0?Math.max(0,policy.monthlyTokens()-monthly.inputTokens()-monthly.outputTokens()):-1;
        String sql="SELECT r.*,s.title session_title,COALESCE((SELECT SUM((CASE WHEN u.input_tokens>0 THEN u.input_tokens ELSE u.estimated_input_tokens END)+u.output_tokens) FROM model_usage u WHERE u.run_id=r.id),0) used_tokens FROM runs r JOIN sessions s ON s.id=r.session_id " +
                "WHERE s.project_key=? AND r.status IN ('QUEUED','RUNNING','WAITING_MODEL','WAITING_TOOL','WAITING_APPROVAL','FAILED') " +
                "ORDER BY CASE r.status WHEN 'RUNNING' THEN 0 WHEN 'WAITING_MODEL' THEN 1 WHEN 'WAITING_TOOL' THEN 2 " +
                "WHEN 'WAITING_APPROVAL' THEN 3 WHEN 'QUEUED' THEN 4 ELSE 5 END,r.priority DESC,r.created_at";
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,key);try(ResultSet rs=ps.executeQuery()){while(rs.next()){
                RunRecord run=run(rs);values.add(new QueueItem(run,rs.getString("session_title"),
                        run.startedAt()==null?0:Math.max(0,ChronoUnit.MILLIS.between(run.startedAt(),Instant.now())),
                        rs.getLong("used_tokens"),remaining));}}
            return values;
        }catch(SQLException e){throw failure("list run queue",e);}
    }

    public boolean setPriority(String runId,int priority){
        try(Connection c=open();PreparedStatement ps=c.prepareStatement("UPDATE runs SET priority=? WHERE id=? AND status='QUEUED'")){
            ps.setInt(1,Math.max(-10,Math.min(priority,10)));ps.setString(2,runId);return ps.executeUpdate()>0;
        }catch(SQLException e){throw failure("update run priority",e);}
    }

    public boolean requeue(String runId){
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "UPDATE runs SET status='QUEUED',error=NULL,queued_at=?,finished_at=NULL,started_at=NULL,retry_count=retry_count+1,version=version+1 " +
                        "WHERE id=? AND status IN ('FAILED','CANCELED')")){
            ps.setString(1,Instant.now().toString());ps.setString(2,runId);return ps.executeUpdate()>0;
        }catch(SQLException e){throw failure("requeue run",e);}
    }

    public List<ScheduledTask> schedules(String projectKey){return schedules(projectKey,false);}
    public List<ScheduledTask> dueSchedules(){return schedules(null,true);}
    private List<ScheduledTask> schedules(String projectKey,boolean due){
        List<ScheduledTask> values=new ArrayList<>();String sql=due
                ?"SELECT * FROM scheduled_tasks WHERE enabled=1 AND next_run_at IS NOT NULL AND next_run_at<=? ORDER BY next_run_at LIMIT 20"
                :"SELECT * FROM scheduled_tasks WHERE project_key=? ORDER BY enabled DESC,next_run_at";
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,due?Instant.now().toString():project(projectKey));
            try(ResultSet rs=ps.executeQuery()){while(rs.next())values.add(schedule(rs));}return values;
        }catch(SQLException e){throw failure("list scheduled tasks",e);}
    }

    public ScheduledTask saveSchedule(String id,String projectKey,String name,String templateId,String type,
                                      String value,String variablesJson,boolean enabled,Instant nextRunAt){
        String key=project(projectKey),resolvedId=blank(id)?id("schedule"):id.trim();Instant now=Instant.now();
        String scheduleType=text(type,"scheduleType",20).toUpperCase();
        if(!List.of("ONCE","DAILY","WEEKLY","CRON").contains(scheduleType))throw new IllegalArgumentException("unsupported schedule type");
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "INSERT INTO scheduled_tasks(id,project_key,name,template_id,schedule_type,schedule_value,variables_json,enabled,next_run_at,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,template_id=excluded.template_id,"+
                        "schedule_type=excluded.schedule_type,schedule_value=excluded.schedule_value,variables_json=excluded.variables_json,"+
                        "enabled=excluded.enabled,next_run_at=excluded.next_run_at,updated_at=excluded.updated_at")){
            int i=1;ps.setString(i++,resolvedId);ps.setString(i++,key);ps.setString(i++,text(name,"name",120));
            ps.setString(i++,text(templateId,"templateId",160));ps.setString(i++,scheduleType);ps.setString(i++,value(value,120));
            ps.setString(i++,json(variablesJson));ps.setInt(i++,enabled?1:0);ps.setString(i++,nextRunAt==null?null:nextRunAt.toString());
            ps.setString(i++,now.toString());ps.setString(i,now.toString());ps.executeUpdate();return findSchedule(resolvedId).orElseThrow();
        }catch(SQLException e){throw failure("save scheduled task",e);}
    }

    public Optional<ScheduledTask> findSchedule(String id){
        try(Connection c=open();PreparedStatement ps=c.prepareStatement("SELECT * FROM scheduled_tasks WHERE id=?")){
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(schedule(rs)):Optional.empty();}
        }catch(SQLException e){throw failure("find scheduled task",e);}
    }
    public boolean claimSchedule(String id){
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "UPDATE scheduled_tasks SET next_run_at=NULL,updated_at=? WHERE id=? AND enabled=1 " +
                        "AND next_run_at IS NOT NULL AND next_run_at<=?")){
            String now=Instant.now().toString();ps.setString(1,now);ps.setString(2,id);ps.setString(3,now);
            return ps.executeUpdate()>0;
        }catch(SQLException e){throw failure("claim scheduled task",e);}
    }
    public void retrySchedule(String id,Instant next){
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "UPDATE scheduled_tasks SET next_run_at=?,updated_at=? WHERE id=? AND enabled=1")){
            ps.setString(1,next.toString());ps.setString(2,Instant.now().toString());ps.setString(3,id);ps.executeUpdate();
        }catch(SQLException e){throw failure("retry scheduled task",e);}
    }
    public void completeSchedule(String id,String runId,Instant next){
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "UPDATE scheduled_tasks SET last_run_at=?,last_run_id=?,next_run_at=?,enabled=CASE WHEN schedule_type='ONCE' THEN 0 ELSE enabled END,updated_at=? WHERE id=?")){
            String now=Instant.now().toString();ps.setString(1,now);ps.setString(2,runId);ps.setString(3,next==null?null:next.toString());ps.setString(4,now);ps.setString(5,id);ps.executeUpdate();
        }catch(SQLException e){throw failure("complete scheduled task",e);}
    }
    public boolean deleteSchedule(String id){return delete("scheduled_tasks",id);}

    public List<NotificationChannel> notificationChannels(String projectKey){
        List<NotificationChannel> values=new ArrayList<>();try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "SELECT * FROM notification_channels WHERE project_key=? ORDER BY name")){
            ps.setString(1,project(projectKey));try(ResultSet rs=ps.executeQuery()){while(rs.next())values.add(channel(rs));}return values;
        }catch(SQLException e){throw failure("list notification channels",e);}
    }
    public NotificationChannel saveNotification(String id,String projectKey,String name,String type,String endpoint,
                                                String secretEnv,String events,boolean enabled){
        String key=project(projectKey),resolvedId=blank(id)?id("notify"):id.trim();Instant now=Instant.now();
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "INSERT INTO notification_channels(id,project_key,name,type,endpoint,secret_env,events,enabled,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,type=excluded.type,"+
                        "endpoint=excluded.endpoint,secret_env=excluded.secret_env,events=excluded.events,enabled=excluded.enabled,updated_at=excluded.updated_at")){
            int i=1;ps.setString(i++,resolvedId);ps.setString(i++,key);ps.setString(i++,text(name,"name",120));ps.setString(i++,text(type,"type",30).toUpperCase());
            ps.setString(i++,value(endpoint,500));ps.setString(i++,env(secretEnv));ps.setString(i++,text(events,"events",300));ps.setInt(i++,enabled?1:0);
            ps.setString(i++,now.toString());ps.setString(i,now.toString());ps.executeUpdate();return notificationChannels(key).stream().filter(v->v.id().equals(resolvedId)).findFirst().orElseThrow();
        }catch(SQLException e){throw failure("save notification channel",e);}
    }
    public boolean deleteNotification(String id){return delete("notification_channels",id);}

    public void enqueueNotification(NotificationChannel channel,String event,String runId,String message){
        String now=Instant.now().toString();try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "INSERT INTO notification_outbox(id,channel_id,project_key,event_type,run_id,message,status,"+
                        "attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'PENDING',0,?,?,?)")){
            ps.setString(1,id("outbox"));ps.setString(2,channel.id());ps.setString(3,channel.projectKey());
            ps.setString(4,event);ps.setString(5,runId);ps.setString(6,message==null?"":message);
            ps.setString(7,now);ps.setString(8,now);ps.setString(9,now);ps.executeUpdate();
        }catch(SQLException e){throw failure("enqueue notification",e);}
    }

    public Optional<NotificationDelivery> claimNotification(){
        try(Connection c=open()){c.setAutoCommit(false);try{
            NotificationDelivery selected=null;try(PreparedStatement ps=c.prepareStatement(
                    "SELECT o.*,c.name channel_name,c.type channel_type,c.endpoint,c.secret_env,c.events,c.enabled,"+
                            "c.created_at channel_created_at,c.updated_at channel_updated_at FROM notification_outbox o "+
                            "JOIN notification_channels c ON c.id=o.channel_id WHERE o.status='PENDING' "+
                            "AND o.next_attempt_at<=? ORDER BY o.next_attempt_at LIMIT 1")){
                ps.setString(1,Instant.now().toString());try(ResultSet r=ps.executeQuery()){if(r.next())selected=delivery(r);}
            }
            if(selected==null){c.commit();return Optional.empty();}
            try(PreparedStatement ps=c.prepareStatement("UPDATE notification_outbox SET status='SENDING',"+
                    "attempts=attempts+1,updated_at=? WHERE id=? AND status='PENDING'")){
                ps.setString(1,Instant.now().toString());ps.setString(2,selected.id());
                if(ps.executeUpdate()==0){c.rollback();return Optional.empty();}
            }
            c.commit();return Optional.of(new NotificationDelivery(selected.id(),selected.channel(),selected.event(),
                    selected.runId(),selected.message(),selected.attempts()+1));
        }catch(Exception e){try{c.rollback();}catch(Exception ignored){}throw e;}}
        catch(SQLException e){throw failure("claim notification",e);}
    }

    public void finishNotification(String id,boolean success,int attempts,String error){
        String status=success?"SENT":attempts>=5?"DEAD":"PENDING";
        Instant next=success?Instant.now():Instant.now().plus(Math.min(300,1L<<Math.min(attempts,8)),ChronoUnit.SECONDS);
        try(Connection c=open();PreparedStatement ps=c.prepareStatement(
                "UPDATE notification_outbox SET status=?,next_attempt_at=?,error=?,updated_at=? WHERE id=?")){
            ps.setString(1,status);ps.setString(2,next.toString());ps.setString(3,error==null?null:value(error,2000));
            ps.setString(4,Instant.now().toString());ps.setString(5,id);ps.executeUpdate();
        }catch(SQLException e){throw failure("finish notification",e);}
    }

    private boolean delete(String table,String id){
        if(!List.of("task_templates","model_profiles","scheduled_tasks","notification_channels").contains(table))throw new IllegalArgumentException("unsupported table");
        try(Connection c=open();PreparedStatement ps=c.prepareStatement("DELETE FROM "+table+" WHERE id=?")){ps.setString(1,id);return ps.executeUpdate()>0;}
        catch(SQLException e){throw failure("delete "+table,e);}
    }
    private Connection open()throws SQLException{return connections.open();}
    private static TaskTemplate template(ResultSet r)throws SQLException{return new TaskTemplate(r.getString("id"),r.getString("project_key"),r.getString("name"),r.getString("shortcut"),r.getString("prompt"),r.getString("variables_json"),r.getString("attachment_requirements"),r.getString("allowed_tools"),r.getString("model_profile_id"),instant(r.getString("created_at")),instant(r.getString("updated_at")),instant(r.getString("last_used_at")),r.getInt("use_count"));}
    private static ModelProfile profile(ResultSet r)throws SQLException{return new ModelProfile(r.getString("id"),r.getString("project_key"),r.getString("name"),r.getString("base_url"),r.getString("api_key_env"),r.getString("model"),r.getString("fallback_model"),r.getInt("max_context_tokens"),r.getInt("max_output_tokens"),r.getDouble("input_price"),r.getDouble("output_price"),r.getInt("local_model")!=0,r.getInt("is_default")!=0,instant(r.getString("created_at")),instant(r.getString("updated_at")));}
    private static ScheduledTask schedule(ResultSet r)throws SQLException{return new ScheduledTask(r.getString("id"),r.getString("project_key"),r.getString("name"),r.getString("template_id"),r.getString("schedule_type"),r.getString("schedule_value"),r.getString("variables_json"),r.getInt("enabled")!=0,instant(r.getString("next_run_at")),instant(r.getString("last_run_at")),r.getString("last_run_id"),instant(r.getString("created_at")),instant(r.getString("updated_at")));}
    private static NotificationChannel channel(ResultSet r)throws SQLException{return new NotificationChannel(r.getString("id"),r.getString("project_key"),r.getString("name"),r.getString("type"),r.getString("endpoint"),r.getString("secret_env"),r.getString("events"),r.getInt("enabled")!=0,instant(r.getString("created_at")),instant(r.getString("updated_at")));}
    private static NotificationDelivery delivery(ResultSet r)throws SQLException{
        NotificationChannel channel=new NotificationChannel(r.getString("channel_id"),r.getString("project_key"),
                r.getString("channel_name"),r.getString("channel_type"),r.getString("endpoint"),
                r.getString("secret_env"),r.getString("events"),r.getInt("enabled")!=0,
                instant(r.getString("channel_created_at")),instant(r.getString("channel_updated_at")));
        return new NotificationDelivery(r.getString("id"),channel,r.getString("event_type"),r.getString("run_id"),
                r.getString("message"),r.getInt("attempts"));
    }
    private static RunRecord run(ResultSet r)throws SQLException{return new RunRecord(r.getString("id"),r.getString("session_id"),RunStatus.valueOf(r.getString("status")),r.getString("input"),r.getInt("current_step"),r.getString("error"),r.getString("thinking_mode"),r.getString("reasoning_effort"),r.getInt("priority"),r.getString("model_profile_id"),r.getInt("retry_count"),instant(r.getString("created_at")),instant(r.getString("started_at")),instant(r.getString("finished_at")),r.getLong("version"));}
    private static String project(String v){String x=blank(v)?"default":v.trim();if(!x.matches("[a-zA-Z0-9_.-]{1,80}"))throw new IllegalArgumentException("invalid projectKey");return x;}
    private static String text(String v,String n,int max){if(blank(v))throw new IllegalArgumentException(n+" must not be blank");return value(v,max);}
    private static String value(String v,int max){String x=v==null?"":v.trim();if(x.length()>max)throw new IllegalArgumentException("value is too long");return x;}
    private static String env(String v){String x=value(v,120);if(!x.isEmpty()&&!x.matches("[A-Z][A-Z0-9_]{1,119}"))throw new IllegalArgumentException("secret env must be an environment variable name");return x;}
    private static String json(String v){String x=blank(v)?"{}":v.trim();if(x.length()>16_000)throw new IllegalArgumentException("json is too long");return x;}
    private static String nullable(String v){return blank(v)?null:v.trim();}
    private static boolean blank(String v){return v==null||v.isBlank();}
    private static String id(String p){return p+"_"+UUID.randomUUID().toString().replace("-","").substring(0,16);}
    private static Instant instant(String v){return blank(v)?null:Instant.parse(v);}
    private static IllegalStateException failure(String a,SQLException e){return new IllegalStateException("SQLite failed to "+a+": "+e.getMessage(),e);}

    public record TaskTemplate(String id,String projectKey,String name,String shortcut,String prompt,String variablesJson,
                               String attachmentRequirements,String allowedTools,String modelProfileId,Instant createdAt,
                               Instant updatedAt,Instant lastUsedAt,int useCount){}
    public record ModelProfile(String id,String projectKey,String name,String baseUrl,String apiKeyEnv,String model,
                               String fallbackModel,int maxContextTokens,int maxOutputTokens,double inputPrice,
                               double outputPrice,boolean localModel,boolean defaultProfile,Instant createdAt,Instant updatedAt){}
    public record BudgetPolicy(String projectKey,long dailyTokens,long monthlyTokens,double dailyCost,double monthlyCost,
                               double warnRatio,int maxConcurrentRuns,Instant updatedAt){}
    public record UsageSummary(String projectKey,int days,long calls,long inputTokens,long outputTokens,long cachedTokens,
                               long averageDurationMs,double failureRate,long retries,double estimatedCost,BudgetPolicy budget,
                               List<UsageBreakdown> breakdown){}
    public record UsageBreakdown(String date,String sessionId,String sessionTitle,String model,long calls,long inputTokens,
                                 long outputTokens,long cachedTokens,long averageDurationMs,long retries,double estimatedCost,
                                 boolean localModel){}
    public record QueueItem(RunRecord run,String sessionTitle,long elapsedMs,long usedTokens,long remainingBudgetTokens){}
    public record ScheduledTask(String id,String projectKey,String name,String templateId,String scheduleType,
                                String scheduleValue,String variablesJson,boolean enabled,Instant nextRunAt,
                                Instant lastRunAt,String lastRunId,Instant createdAt,Instant updatedAt){}
    public record NotificationChannel(String id,String projectKey,String name,String type,String endpoint,String secretEnv,
                                      String events,boolean enabled,Instant createdAt,Instant updatedAt){}
    public record NotificationDelivery(String id,NotificationChannel channel,String event,String runId,
                                       String message,int attempts){}
}
