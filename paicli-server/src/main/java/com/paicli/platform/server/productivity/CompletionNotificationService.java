package com.paicli.platform.server.productivity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.web.NetworkPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

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
            executor.execute(()->deliver(channel,event,runId,message));
        }
    }
    private void deliver(ProductivityStore.NotificationChannel channel,String event,String runId,String message){
        try{
            NetworkPolicy.requirePublicHttpUrl(channel.endpoint());
            byte[] body=mapper.writeValueAsBytes(Map.of("event",event,"projectKey",channel.projectKey(),"runId",runId,
                    "message",message==null?"":message,"occurredAt",Instant.now().toString(),"channelType",channel.type()));
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(channel.endpoint())).timeout(Duration.ofSeconds(20))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if(!channel.secretEnv().isBlank()){
                String secret=System.getenv(channel.secretEnv());if(secret!=null&&!secret.isBlank())builder.header("Authorization","Bearer "+secret);
            }
            client.send(builder.build(),HttpResponse.BodyHandlers.discarding());
        }catch(Exception ignored){ }
    }
    private static boolean contains(String events,String event){for(String value:events.split(","))if(value.trim().equalsIgnoreCase(event))return true;return false;}
}
