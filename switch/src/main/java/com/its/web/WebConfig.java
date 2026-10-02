package com.its.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Paths that need X-Participant + X-Api-Key (also used to document security in the OpenAPI spec). */
    public static final List<String> PARTICIPANT_PATHS =
        List.of("/api/v1/proxies/**", "/api/v1/iso/**", "/api/v1/settlement/cycles/*/transactions");
    /** Paths that need X-Admin-Key. */
    public static final List<String> ADMIN_PATHS = List.of("/api/v1/settlement/close");

    private final ParticipantAuthInterceptor participantAuth;
    private final AdminKeyInterceptor adminKey;

    public WebConfig(ParticipantAuthInterceptor participantAuth, AdminKeyInterceptor adminKey) {
        this.participantAuth = participantAuth;
        this.adminKey = adminKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(participantAuth).addPathPatterns(PARTICIPANT_PATHS);
        registry.addInterceptor(adminKey).addPathPatterns(ADMIN_PATHS);
    }
}
