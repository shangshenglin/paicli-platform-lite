package com.paicli.platform.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class SandboxAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxAgentApplication.class, args);
    }
}

