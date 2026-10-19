package com.personaltrader.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Short lived, bounded counters. Never stores passwords, authorization headers, or usernames. */
final class LoginThrottleFilter extends OncePerRequestFilter {
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private record Attempt(int failures, long startedAt) {}

    private synchronized boolean blocked(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MS);
        Attempt attempt = attempts.get(request.getRemoteAddr());
        return attempt != null && attempt.failures() >= 5;
    }

    synchronized void failed(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MS);
        if (!attempts.containsKey(address) && attempts.size() >= 1024) return;
        Attempt old = attempts.get(address);
        attempts.put(address, new Attempt(old == null ? 1 : old.failures() + 1, old == null ? now : old.startedAt()));
    }

    synchronized void succeeded(HttpServletRequest request) { attempts.remove(request.getRemoteAddr()); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean formAttempt = request.getMethod().equals("POST") && request.getServletPath().equals("/login");
        boolean basicAttempt = request.getHeader("Authorization") != null;
        if ((formAttempt || basicAttempt) && blocked(request)) {
            response.setHeader("Retry-After", "600");
            SecurityConfiguration.jsonError(response, 429, "LOGIN_RETRY_LATER"); return;
        }
        chain.doFilter(request, response);
    }
}
