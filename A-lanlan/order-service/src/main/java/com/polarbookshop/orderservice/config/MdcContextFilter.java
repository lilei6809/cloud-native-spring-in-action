package com.polarbookshop.orderservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jboss.logging.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class MdcContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String tennantId = request.getHeader("x-tenant-id");
            String userId = request.getHeader("x-user-id");

            if (tennantId != null) MDC.put("tenantId", tennantId);
            if (userId != null) MDC.put("userId", userId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }
}
