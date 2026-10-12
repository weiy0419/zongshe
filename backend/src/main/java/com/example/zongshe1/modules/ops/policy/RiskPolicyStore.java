package com.example.zongshe1.modules.ops.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前授信策略只保留一份，键不过期。连接失败和内容损坏分开返回，方便读取降级、写入拒绝。
 */
@Component
@Slf4j
public class RiskPolicyStore {

    static final String KEY = "ops:risk-policy";

    public enum Kind {
        FOUND, MISSING, UNAVAILABLE, CORRUPT
    }

    public record Loaded(Kind kind, List<String> enabledModels, int policyVersion) {
        static Loaded found(List<String> enabledModels, int policyVersion) {
            return new Loaded(Kind.FOUND, List.copyOf(enabledModels), policyVersion);
        }

        static Loaded missing() {
            return new Loaded(Kind.MISSING, List.of(), 0);
        }

        static Loaded unavailable() {
            return new Loaded(Kind.UNAVAILABLE, List.of(), 0);
        }

        static Loaded corrupt() {
            return new Loaded(Kind.CORRUPT, List.of(), 0);
        }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RiskPolicyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Loaded load() {
        String raw;
        try {
            raw = redis.opsForValue().get(KEY);
        } catch (RuntimeException ex) {
            log.warn("risk-policy Redis GET failed: {}", ex.getMessage());
            return Loaded.unavailable();
        }
        if (raw == null || raw.isBlank()) {
            return Loaded.missing();
        }
        try {
            return parse(raw);
        } catch (RuntimeException ex) {
            log.warn("risk-policy Redis value cannot be read: {}", ex.getMessage());
            return Loaded.corrupt();
        }
    }

    public void save(List<String> enabledModels, int policyVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabledModels", enabledModels);
        body.put("policyVersion", policyVersion);
        try {
            redis.opsForValue().set(KEY, mapper.writeValueAsString(body));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("risk-policy Redis SET failed: {}", ex.getMessage());
            throw new RiskPolicyStoreException("策略存储不可用，未保存", ex);
        }
    }

    private Loaded parse(String raw) {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        JsonNode modelsNode = node.get("enabledModels");
        JsonNode versionNode = node.get("policyVersion");
        if (modelsNode == null || !modelsNode.isArray() || versionNode == null || !versionNode.isIntegralNumber()) {
            return Loaded.corrupt();
        }
        List<String> models = new ArrayList<>();
        for (JsonNode item : modelsNode) {
            if (!item.isTextual()) {
                return Loaded.corrupt();
            }
            models.add(item.asText());
        }
        int version = versionNode.intValue();
        if (version < 1) {
            return Loaded.corrupt();
        }
        return Loaded.found(models, version);
    }
}
