package com.example.zongshe1.modules.ops.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditLogService auditLogService;

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> data = auditLogService.latest().stream()
                .map(this::toItem)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private Map<String, Object> toItem(AuditLog row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("occurredAt", row.getOccurredAt() == null ? null : row.getOccurredAt().format(TIME));
        item.put("userId", row.getUserId());
        item.put("path", row.getPath());
        item.put("resultCode", row.getResultCode());
        return item;
    }
}
