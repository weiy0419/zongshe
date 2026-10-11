package com.example.zongshe1.modules.ops.audit;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationAuditFilterTest {

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private OperationAuditFilter filter;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userLoginWritesUserIdPathAndResultCode() throws Exception {
        MockHttpServletRequest request = post("/api/users/login",
                "{\"phoneNumber\":\"13800138000\",\"password\":\"secret\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            HttpServletResponse http = (HttpServletResponse) resp;
            req.getInputStream().readAllBytes();
            http.setStatus(200);
            http.setContentType("application/json");
            http.getWriter().write("{\"success\":true,\"userInfo\":{\"userId\":\"u-9\"}}");
        });

        assertEquals("{\"success\":true,\"userInfo\":{\"userId\":\"u-9\"}}", response.getContentAsString());
        verify(auditLogService).record("u-9", "/api/users/login", 200);
    }

    @Test
    void failedAdminLoginFallsBackToUsernameAndKeepsPasswordOut() throws Exception {
        MockHttpServletRequest request = post("/api/admin/login",
                "{\"username\":\"yunizai\",\"password\":\"wrong\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            HttpServletResponse http = (HttpServletResponse) resp;
            req.getInputStream().readAllBytes();
            http.setStatus(400);
            http.getWriter().write("{\"success\":false,\"message\":\"用户名或密码错误\"}");
        });

        verify(auditLogService).record("yunizai", "/api/admin/login", 400);
    }

    @Test
    void createApplicationUsesTokenSubject() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-42", null, List.of()));
        MockHttpServletRequest request = post("/api/loan-applications/submit", "{\"amount\":1000}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> ((HttpServletResponse) resp).setStatus(200));

        verify(auditLogService).record("user-42", "/api/loan-applications/submit", 200);
    }

    @Test
    void otherPathsAreNotAudited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = (req, resp) -> ((HttpServletResponse) resp).setStatus(200);

        filter.doFilter(request, response, chain);

        verify(auditLogService, never()).record(anyString(), anyString(), anyInt());
    }

    @Test
    void auditWriteFailureDoesNotBreakLogin() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(auditLogService).record(nullable(String.class), anyString(), anyInt());
        MockHttpServletRequest request = post("/api/users/login-otp", "{\"phoneNumber\":\"13900139000\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            HttpServletResponse http = (HttpServletResponse) resp;
            req.getInputStream().readAllBytes();
            http.setStatus(200);
            http.getWriter().write("{\"success\":true,\"userInfo\":{\"userId\":\"otp-1\"}}");
        });

        assertEquals(200, response.getStatus());
        assertEquals("{\"success\":true,\"userInfo\":{\"userId\":\"otp-1\"}}", response.getContentAsString());
    }

    @Test
    void listExposesTheFourFields() {
        AuditLog row = new AuditLog();
        row.setId(3L);
        row.setOccurredAt(LocalDateTime.of(2026, 10, 10, 23, 40, 1));
        row.setUserId("u-9");
        row.setPath("/api/users/login");
        row.setResultCode(200);
        when(auditLogService.latest()).thenReturn(List.of(row));

        Map<String, Object> body = new AuditController(auditLogService).list();

        assertEquals(true, body.get("success"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertEquals(1, data.size());
        assertEquals("2026-10-10 23:40:01", data.get(0).get("occurredAt"));
        assertEquals("u-9", data.get(0).get("userId"));
        assertEquals("/api/users/login", data.get(0).get("path"));
        assertEquals(200, data.get(0).get("resultCode"));
        assertNull(data.get(0).get("password"));
    }

    private static MockHttpServletRequest post(String path, String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
