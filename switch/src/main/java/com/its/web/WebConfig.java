package com.its.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ParticipantAuthInterceptor participantAuth;
    private final AdminKeyInterceptor adminKey;

    public WebConfig(ParticipantAuthInterceptor participantAuth, AdminKeyInterceptor adminKey) {
        this.participantAuth = participantAuth;
        this.adminKey = adminKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(participantAuth)
            .addPathPatterns("/api/v1/proxies/**", "/api/v1/iso/**", "/api/v1/settlement/cycles/*/transactions");
        registry.addInterceptor(adminKey).addPathPatterns("/api/v1/settlement/close");
    }
}
