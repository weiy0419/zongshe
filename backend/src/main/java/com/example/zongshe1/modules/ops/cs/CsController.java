package com.example.zongshe1.modules.ops.cs;

import com.example.zongshe1.modules.ops.security.BearerRoleResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已登录用户可查看 FAQ 并发问。本周回答一律来自本地 FAQ。
 */
@RestController
@RequestMapping("/api/ops/cs")
@RequiredArgsConstructor
public class CsController {

    private final CsAskService csAskService;
    private final BearerRoleResolver roles;

    @GetMapping("/faq")
    public ResponseEntity<Map<String, Object>> faq(HttpServletRequest request) {
        if (roles.role(request).isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        return ResponseEntity.ok(csAskService.faq());
    }

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(HttpServletRequest request, @RequestBody CsAskRequest body) {
        if (roles.role(request).isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        if (body == null || body.getQuestion() == null || body.getQuestion().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        return ResponseEntity.ok(csAskService.ask(body.getQuestion()));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
