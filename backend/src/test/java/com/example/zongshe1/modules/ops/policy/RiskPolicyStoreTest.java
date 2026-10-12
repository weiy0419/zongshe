package com.example.zongshe1.modules.ops.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicyStoreTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private RiskPolicyStore store;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        store = new RiskPolicyStore(redis);
    }

    @Test
    void blankKeyIsMissing() {
        when(values.get(RiskPolicyStore.KEY)).thenReturn("  ");

        assertEquals(RiskPolicyStore.Kind.MISSING, store.load().kind());
    }

    @Test
    void connectionFailureIsUnavailable() {
        when(values.get(RiskPolicyStore.KEY)).thenThrow(new RuntimeException("connection refused"));

        assertEquals(RiskPolicyStore.Kind.UNAVAILABLE, store.load().kind());
    }

    @Test
    void brokenJsonIsCorrupt() {
        when(values.get(RiskPolicyStore.KEY)).thenReturn("{\"enabledModels\":[\"WOE\"],\"policyVersion\":\"2\"}");

        assertEquals(RiskPolicyStore.Kind.CORRUPT, store.load().kind());
    }

    @Test
    void saveWritesNumberVersionWithoutTtl() throws Exception {
        store.save(java.util.List.of("WOE", "EXTERNAL"), 2);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(org.mockito.ArgumentMatchers.eq(RiskPolicyStore.KEY), json.capture());
        JsonNode node = new ObjectMapper().readTree(json.getValue());
        assertEquals("WOE", node.get("enabledModels").get(0).asText());
        assertEquals("EXTERNAL", node.get("enabledModels").get(1).asText());
        assertTrue(node.get("policyVersion").isIntegralNumber());
        assertEquals(2, node.get("policyVersion").intValue());
    }

    @Test
    void saveFailureDoesNotPretendSuccess() {
        org.mockito.Mockito.doThrow(new RuntimeException("readonly"))
                .when(values).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        assertThrows(RiskPolicyStoreException.class, () -> store.save(java.util.List.of("WOE"), 1));
    }
}
