package com.example.zongshe1.modules.ops.policy;

import com.example.zongshe1.modules.ops.security.BearerRoleResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GET 凭任意有效登录令牌；PUT 只接受 role=admin。
 * 成功体保持顶层 enabledModels、policyVersion，供申贷直接读取。
 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class RiskPolicyController {

    private final RiskPolicyService riskPolicyService;
    private final BearerRoleResolver roles;

    @GetMapping("/risk-policy")
    public ResponseEntity<Map<String, Object>> current(HttpServletRequest request) {
        if (roles.role(request).isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        return ResponseEntity.ok(riskPolicyService.current());
    }

    @PutMapping("/risk-policy")
    public ResponseEntity<Map<String, Object>> replace(HttpServletRequest request,
                                                       @RequestBody RiskPolicyUpdateRequest body) {
        Optional<String> role = roles.role(request);
        if (role.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        if (!roles.isAdmin(role.get())) {
            return error(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return ResponseEntity.ok(riskPolicyService.replace(body.getEnabledModels(), body.getHardRulesEnabled()));
    }

    @ExceptionHandler(RiskPolicyStoreException.class)
    public ResponseEntity<Map<String, Object>> storeUnavailable(RiskPolicyStoreException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "POLICY_STORE_UNAVAILABLE");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
