package com.example.zongshe1.modules.ops.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 登录、创建申请结束后写审计，不改信贷和认证业务类。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class OperationAuditFilter extends OncePerRequestFilter {

    static final String ADMIN_LOGIN = "/api/admin/login";
    static final String USER_LOGIN = "/api/users/login";
    static final String USER_OTP_LOGIN = "/api/users/login-otp";
    static final String CREATE_APPLICATION = "/api/loan-applications/submit";

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isAuditTarget(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        int resultCode = HttpServletResponse.SC_OK;
        Exception failure = null;
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
            resultCode = responseWrapper.getStatus();
        } catch (ServletException | IOException | RuntimeException ex) {
            resultCode = responseWrapper.getStatus() >= 400
                    ? responseWrapper.getStatus()
                    : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            failure = ex;
        } finally {
            try {
                String path = pathOf(request);
                String userId = resolveUserId(path, requestWrapper, responseWrapper);
                auditLogService.record(userId, path, resultCode);
            } catch (Exception ex) {
                log.warn("审计采集失败: {}", ex.getMessage());
            }
            responseWrapper.copyBodyToResponse();
        }
        if (failure instanceof ServletException servletException) {
            throw servletException;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }

    static boolean isAuditTarget(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = pathOf(request);
        return ADMIN_LOGIN.equals(path)
                || USER_LOGIN.equals(path)
                || USER_OTP_LOGIN.equals(path)
                || CREATE_APPLICATION.equals(path);
    }

    static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private String resolveUserId(String path,
                                 ContentCachingRequestWrapper request,
                                 ContentCachingResponseWrapper response) {
        if (CREATE_APPLICATION.equals(path)) {
            String fromToken = authenticatedName();
            if (fromToken != null) {
                return clip(fromToken);
            }
        }
        String fromResponse = readResponseIdentity(response.getContentAsByteArray());
        if (fromResponse != null) {
            return clip(fromResponse);
        }
        return clip(readRequestIdentity(request.getContentAsByteArray()));
    }

    private String authenticatedName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return name;
    }

    private String readResponseIdentity(byte[] body) {
        JsonNode root = readJson(body);
        if (root == null) {
            return null;
        }
        JsonNode userInfo = root.get("userInfo");
        if (userInfo != null && userInfo.hasNonNull("userId")) {
            return userInfo.get("userId").asText();
        }
        JsonNode admin = root.get("admin");
        if (admin != null && admin.hasNonNull("username")) {
            return admin.get("username").asText();
        }
        if (admin != null && admin.hasNonNull("id")) {
            return admin.get("id").asText();
        }
        return null;
    }

    private String readRequestIdentity(byte[] body) {
        JsonNode root = readJson(body);
        if (root == null) {
            return null;
        }
        if (root.hasNonNull("username")) {
            return root.get("username").asText();
        }
        if (root.hasNonNull("phoneNumber")) {
            return root.get("phoneNumber").asText();
        }
        return null;
    }

    private JsonNode readJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
        if (trimmed.length() <= 64 && bytes.length <= 64) {
            return trimmed;
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 64));
    }
}
