package com.example.zongshe1.modules.ops.security;

import com.example.zongshe1.common.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 从 Authorization 读取已验签的 role。登录过滤器不把角色放进权限列表，平台接口在这里自己判断。
 */
@Component
@RequiredArgsConstructor
public class BearerRoleResolver {

    private final JwtUtil jwtUtil;

    public Optional<String> role(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring(7).trim();
        if (token.isEmpty() || !jwtUtil.validateToken(token)) {
            return Optional.empty();
        }
        String role = jwtUtil.getRoleFromToken(token);
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(role.trim());
    }

    public boolean isAdmin(String role) {
        return role != null && "admin".equalsIgnoreCase(role);
    }
}
