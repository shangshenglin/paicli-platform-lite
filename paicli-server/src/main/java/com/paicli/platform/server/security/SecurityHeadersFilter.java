package com.paicli.platform.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private static final String CONSOLE_CSP = "default-src 'self'; "
            + "base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
            + "img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; connect-src 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (isConsoleResource(request.getRequestURI())) {
            response.setHeader("Content-Security-Policy", CONSOLE_CSP);
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isConsoleResource(String path) {
        return path.equals("/") || path.equals("/index.html")
                || path.equals("/app.js") || path.equals("/app.css");
    }
}
