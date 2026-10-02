package com.its.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.its.config.SwitchProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Operator actions (closing a settlement cycle) need the X-Admin-Key header. */
@Component
public class AdminKeyInterceptor implements HandlerInterceptor {

    private final byte[] adminKey;

    public AdminKeyInterceptor(SwitchProperties props) {
        this.adminKey = props.adminKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getHeader("X-Admin-Key");
        if (key == null || !MessageDigest.isEqual(adminKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Admin key required");
        }
        return true;
    }
}
