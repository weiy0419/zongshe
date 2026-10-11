package com.example.zongshe1.modules.ops.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository repository;

    @InjectMocks
    private AuditLogService service;

    @Test
    void recordPersistsTimeUserPathAndResultCode() {
        when(repository.save(org.mockito.ArgumentMatchers.any(AuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.record("  u-9  ", "/api/loan-applications/submit", 201);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertNotNull(saved.getOccurredAt());
        assertEquals("u-9", saved.getUserId());
        assertEquals("/api/loan-applications/submit", saved.getPath());
        assertEquals(201, saved.getResultCode());
    }

    @Test
    void blankUserIdIsStoredAsNull() {
        when(repository.save(org.mockito.ArgumentMatchers.any(AuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.record("   ", "/api/admin/login", 400);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getUserId());
    }

    @Test
    void repositoryFailureIsSwallowed() {
        when(repository.save(org.mockito.ArgumentMatchers.any(AuditLog.class)))
                .thenThrow(new RuntimeException("db down"));

        service.record("u-1", "/api/users/login", 200);
    }
}
