package com.example.zongshe1.modules.ops.policy;

import com.example.zongshe1.common.exception.BusinessException;
import com.example.zongshe1.modules.ops.policy.RiskPolicyStore.Loaded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicyServiceTest {

    @Mock
    private RiskPolicyStore store;

    @InjectMocks
    private RiskPolicyService service;

    @Test
    void missingStoreReturnsDefaultWithoutFallback() {
        when(store.load()).thenReturn(Loaded.missing());

        Map<String, Object> body = service.current();

        assertEquals(List.of("WOE"), body.get("enabledModels"));
        assertEquals(1, body.get("policyVersion"));
        assertFalse(body.containsKey("fallback"));
    }

    @Test
    void unavailableStoreReturnsDefaultAndMarksFallback() {
        when(store.load()).thenReturn(Loaded.unavailable());

        Map<String, Object> body = service.current();

        assertEquals(List.of("WOE"), body.get("enabledModels"));
        assertEquals(1, body.get("policyVersion"));
        assertEquals(Boolean.TRUE, body.get("fallback"));
        assertEquals("策略存储不可用，已返回默认策略", body.get("message"));
    }

    @Test
    void illegalStoredModelsAreNotServed() {
        when(store.load()).thenReturn(Loaded.found(List.of("SCORECARD"), 6));

        Map<String, Object> body = service.current();

        assertEquals(List.of("WOE"), body.get("enabledModels"));
        assertEquals(Boolean.TRUE, body.get("fallback"));
    }

    @Test
    void sameSetKeepsVersionAndCanonicalOrder() {
        when(store.load()).thenReturn(Loaded.found(List.of("EXTERNAL", "WOE"), 4));

        Map<String, Object> body = service.replace(List.of(" external ", "woe", "WOE"), null);

        assertEquals(List.of("WOE", "EXTERNAL"), body.get("enabledModels"));
        assertEquals(4, body.get("policyVersion"));
        verify(store).save(List.of("WOE", "EXTERNAL"), 4);
    }

    @Test
    void changedSetIncrementsVersion() {
        when(store.load()).thenReturn(Loaded.missing());

        Map<String, Object> body = service.replace(List.of("BEHAVIOR", "WOE"), null);

        assertEquals(List.of("WOE", "BEHAVIOR"), body.get("enabledModels"));
        assertEquals(2, body.get("policyVersion"));
        verify(store).save(List.of("WOE", "BEHAVIOR"), 2);
    }

    @Test
    void emptySelectionIsLegalAndBumpsVersion() {
        when(store.load()).thenReturn(Loaded.found(List.of("WOE"), 1));

        service.replace(List.of(), null);

        verify(store).save(List.of(), 2);
    }

    @Test
    void unknownModelDoesNotTouchStore() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.replace(List.of("WOE", "SCORECARD"), null));

        assertEquals("UNKNOWN_MODEL", ex.getCode());
        verify(store, never()).load();
        verify(store, never()).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void hardRuleNameIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.replace(List.of("HARD_RULE"), null));

        assertEquals("HARD_RULE_LOCKED", ex.getCode());
        verify(store, never()).load();
    }

    @Test
    void disablingHardRulesIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.replace(List.of("WOE"), false));

        assertEquals("硬规则不可关闭", ex.getMessage());
        verify(store, never()).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void putRefusesWhenStoreCannotBeRead() {
        when(store.load()).thenReturn(Loaded.unavailable());

        assertThrows(RiskPolicyStoreException.class, () -> service.replace(List.of("WOE"), null));
        verify(store, never()).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void corruptValueCanBeReplacedFromDefault() {
        when(store.load()).thenReturn(Loaded.corrupt());

        service.replace(List.of("EXTERNAL"), true);

        verify(store).save(List.of("EXTERNAL"), 2);
    }
}
