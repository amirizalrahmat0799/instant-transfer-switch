package com.its.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.its.participant.ParticipantRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Banks identify themselves with X-Participant (their BIC) and X-Api-Key. The authenticated participant is put on
 * the request for controllers. (A production switch would use mutual TLS and signed messages instead.)
 */
@Component
public class ParticipantAuthInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "its.participant";

    private final ParticipantRegistry registry;

    public ParticipantAuthInterceptor(ParticipantRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var participant = registry.authenticate(request.getHeader("X-Participant"), request.getHeader("X-Api-Key"))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unknown participant or wrong API key"));
        request.setAttribute(ATTRIBUTE, participant);
        return true;
    }
}
