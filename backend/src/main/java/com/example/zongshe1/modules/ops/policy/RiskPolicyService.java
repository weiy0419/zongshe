package com.example.zongshe1.modules.ops.policy;

import com.example.zongshe1.common.exception.BusinessException;
import com.example.zongshe1.modules.ops.policy.RiskPolicyStore.Loaded;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 授信启用策略。硬规则不进启用列表，请求也不能把它关掉。
 * 版本只在启用集合变化时加一。Redis 不可读时返回默认 WOE，不可写时拒绝保存。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskPolicyService {

    static final List<String> DEFAULT_MODELS = List.of("WOE");
    static final int DEFAULT_VERSION = 1;
    private static final List<String> ORDER = List.of("WOE", "EXTERNAL", "BEHAVIOR");
    private static final Set<String> ALLOWED = Set.copyOf(ORDER);
    private static final Set<String> HARD_RULES = Set.of("HARD_RULE", "HARD_RULES", "HARD", "RULE", "RULES");
    private static final String FALLBACK_MESSAGE = "策略存储不可用，已返回默认策略";

    private final RiskPolicyStore store;

    public Map<String, Object> current() {
        Loaded loaded = store.load();
        return switch (loaded.kind()) {
            case MISSING -> toMap(DEFAULT_MODELS, DEFAULT_VERSION, false);
            case FOUND -> acceptStored(loaded)
                    .map(policy -> toMap(policy.enabledModels(), policy.policyVersion(), false))
                    .orElseGet(() -> toMap(DEFAULT_MODELS, DEFAULT_VERSION, true));
            case CORRUPT, UNAVAILABLE -> toMap(DEFAULT_MODELS, DEFAULT_VERSION, true);
        };
    }

    public Map<String, Object> replace(List<String> enabledModels, Boolean hardRulesEnabled) {
        List<String> normalized = normalize(enabledModels, hardRulesEnabled);
        Loaded loaded = store.load();
        Policy current = baseline(loaded);
        int nextVersion = current.enabledModels().equals(normalized)
                ? current.policyVersion()
                : current.policyVersion() + 1;
        if (nextVersion != current.policyVersion()) {
            log.info("risk-policy version {} -> {} models={}", current.policyVersion(), nextVersion, normalized);
        }
        store.save(normalized, nextVersion);
        return toMap(normalized, nextVersion, false);
    }

    private Policy baseline(Loaded loaded) {
        return switch (loaded.kind()) {
            case UNAVAILABLE -> throw new RiskPolicyStoreException("策略存储不可用，未保存");
            case MISSING -> new Policy(DEFAULT_MODELS, DEFAULT_VERSION);
            case CORRUPT -> {
                log.warn("risk-policy stored value is unusable, next save starts from the default");
                yield new Policy(DEFAULT_MODELS, DEFAULT_VERSION);
            }
            case FOUND -> acceptStored(loaded).orElseGet(() -> {
                log.warn("risk-policy stored models are not a legal set, next save starts from the default");
                return new Policy(DEFAULT_MODELS, DEFAULT_VERSION);
            });
        };
    }

    private Optional<Policy> acceptStored(Loaded loaded) {
        try {
            List<String> models = normalize(loaded.enabledModels(), null);
            if (loaded.policyVersion() < 1) {
                return Optional.empty();
            }
            return Optional.of(new Policy(models, loaded.policyVersion()));
        } catch (BusinessException ex) {
            return Optional.empty();
        }
    }

    static List<String> normalize(List<String> raw, Boolean hardRulesEnabled) {
        if (Boolean.FALSE.equals(hardRulesEnabled)) {
            throw new BusinessException("HARD_RULE_LOCKED", "硬规则不可关闭");
        }
        if (raw == null) {
            throw new BusinessException("INVALID_MODELS", "enabledModels 不能为空");
        }
        Set<String> selected = new HashSet<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                throw new BusinessException("INVALID_MODELS", "enabledModels 含空模型名");
            }
            String name = item.trim().toUpperCase(Locale.ROOT);
            if (HARD_RULES.contains(name)) {
                throw new BusinessException("HARD_RULE_LOCKED", "硬规则不可关闭");
            }
            if (!ALLOWED.contains(name)) {
                throw new BusinessException("UNKNOWN_MODEL", "未知授信模型: " + name);
            }
            selected.add(name);
        }
        return ORDER.stream().filter(selected::contains).toList();
    }

    private static Map<String, Object> toMap(List<String> enabledModels, int policyVersion, boolean fallback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabledModels", enabledModels);
        body.put("policyVersion", policyVersion);
        if (fallback) {
            body.put("fallback", Boolean.TRUE);
            body.put("message", FALLBACK_MESSAGE);
        }
        return body;
    }

    private record Policy(List<String> enabledModels, int policyVersion) {
    }
}
