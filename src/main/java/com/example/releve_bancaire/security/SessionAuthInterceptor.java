package com.example.releve_bancaire.security;

import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.servises.auth.AuthService;
import com.example.releve_bancaire.servises.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class SessionAuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (path.equals("/api/auth/login") || path.equals("/api/auth/logout")) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            writeUnauthorized(response);
            return false;
        }

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            writeUnauthorized(response);
            return false;
        }

        if (handler instanceof HandlerMethod handlerMethod) {
            RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requireRole == null) {
                requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
            }
            if (requireRole != null && !hasRole(sessionUser.role(), requireRole.value())) {
                writeForbidden(response);
                return false;
            }
        }

        return true;
    }

    private boolean hasRole(UserRole role, UserRole[] allowed) {
        return Arrays.stream(allowed).anyMatch(r -> r == role);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"forbidden\"}");
    }
}
