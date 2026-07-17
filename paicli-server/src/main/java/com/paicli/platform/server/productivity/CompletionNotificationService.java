package com.paicli.platform.server.productivity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.web.NetworkPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.task.TaskRejectedException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class CompletionNotificationService {
    private final ProductivityStore store;private final ObjectMapper mapper;private final TaskExecutor executor;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public CompletionNotificationService(ProductivityStore store,ObjectMapper mapper,
                                         @Qualifier("notificationTaskExecutor")TaskExecutor executor){this.store=store;this.mapper=mapper;this.executor=executor;}
    public void publish(String projectKey,String event,String runId,String message){
        for(var channel:store.notificationChannels(projectKey)){
            if(!channel.enabled()||channel.type().equals("BROWSER")||!contains(channel.events(),event)||channel.endpoint().isBlank())continue;
            store.enqueueNotification(channel,event,runId,message);
        }
    }

    @Scheduled(fixedDelayString="${paicli.productivity.notification-delay-ms:1000}")
    public void dispatch(){
        for(int index=0;index<4;index++){
            var delivery=store.claimNotification();if(delivery.isEmpty())return;
            var value=delivery.get();
            try{executor.execute(()->deliver(value));}
            catch(TaskRejectedException e){store.finishNotification(value.id(),false,value.attempts(),e.getMessage());return;}
        }
    }

    private void deliver(ProductivityStore.NotificationDelivery delivery){
        var channel=delivery.channel();String event=delivery.event(),runId=delivery.runId(),message=delivery.message();
        try{
            NetworkPolicy.requirePublicHttpUrl(channel.endpoint());
            byte[] body=mapper.writeValueAsBytes(Map.of("event",event,"projectKey",channel.projectKey(),"runId",runId,
                    "message",message==null?"":message,"occurredAt",Instant.now().toString(),"channelType",channel.type()));
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(channel.endpoint())).timeout(Duration.ofSeconds(20))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if(!channel.secretEnv().isBlank()){
                String secret=System.getenv(channel.secretEnv());if(secret!=null&&!secret.isBlank())builder.header("Authorization","Bearer "+secret);
            }
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("notification HTTP "+response.statusCode());
            store.finishNotification(delivery.id(),true,delivery.attempts(),null);
        }catch(Exception error){store.finishNotification(delivery.id(),false,delivery.attempts(),
                error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
    }
    private static boolean contains(String events,String event){for(String value:events.split(","))if(value.trim().equalsIgnoreCase(event))return true;return false;}
}
