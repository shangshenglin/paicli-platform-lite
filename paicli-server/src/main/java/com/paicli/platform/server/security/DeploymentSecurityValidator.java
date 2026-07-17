package com.paicli.platform.server.security;

import com.paicli.platform.server.config.SecurityProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class DeploymentSecurityValidator {
    private final String serverAddress;
    private final SecurityProperties security;

    public DeploymentSecurityValidator(
            @Value("${server.address:127.0.0.1}") String serverAddress,
            SecurityProperties security) {
        this.serverAddress = serverAddress == null ? "" : serverAddress.trim();
        this.security = security;
    }

    @PostConstruct
    void validate() {
        if (!isLoopback(serverAddress) && !security.enabled()) {
            throw new IllegalStateException(
                    "PAICLI_API_KEY is required when server.address is not a loopback address");
        }
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return true;
        }
        String normalized = address.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            InetAddress resolved = InetAddress.getByName(normalized);
            return resolved.isLoopbackAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Cannot resolve server.address: " + address, exception);
        }
    }
}
