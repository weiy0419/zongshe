package com.example.zongshe1.modules.ops.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository repository;

    /**
     * 审计写失败不能影响登录或申贷。
     */
    public void record(String userId, String path, int resultCode) {
        try {
            AuditLog row = new AuditLog();
            row.setOccurredAt(LocalDateTime.now());
            row.setUserId(blankToNull(userId));
            row.setPath(path);
            row.setResultCode(resultCode);
            repository.save(row);
        } catch (Exception ex) {
            log.warn("审计写入失败 path={} resultCode={}: {}", path, resultCode, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AuditLog> latest() {
        return repository.findTop50ByOrderByOccurredAtDesc();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
