package com.polarbookshop.catalogservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jboss.logging.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在处理请求前, 将关键信息注入 MDC
 */
@Component
public class MdcContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String tennantId = request.getHeader("x-tanant-id");
            String userId = request.getHeader("x-user-id");

            if (tennantId != null) MDC.put("tennantId", tennantId);
            if (userId != null) MDC.put("userId", userId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("tennantId");
            MDC.remove("userId");
        }
    }
}
