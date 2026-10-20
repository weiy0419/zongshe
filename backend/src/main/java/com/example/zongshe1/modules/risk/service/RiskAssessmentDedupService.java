package com.example.zongshe1.modules.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 缓存纯计算结果，不保存 DTO 或报告，命中后由调用方绑定当前申请并持久化。
 * userId 指 User.id；productId 必须由接入层提供，不使用贷款类型代替产品。
 * 仅在启用集合、金额桶及金额评分区间相同时近似复用；调用方须确认其他关键输入等价。
 * 评分参数或规则变化须更换 policy-version；计算回调必须使用与去重相同的启用集合。
 * Redis 不可用时抛出异常，由接入层决定转人工或重试，不宣称已完成去重。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskAssessmentDedupService {

    private static final Duration TTL = Duration.ofSeconds(120);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3]); "
                    + "redis.call('del', KEYS[1]); return 1 else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${risk.dedup.amount-bucket-size:1000}")
    private BigDecimal bucketSize;

    @Value("${risk.dedup.policy-version:w4-v2}")
    private String policyVersion;

    public record Lookup(TwoStageRiskOrchestrator.Result result, boolean reused) {
    }

    /** 兼容旧调用，默认启用 WOE。 */
    public Lookup assess(Long userId, Long productId, BigDecimal amount,
                         Supplier<TwoStageRiskOrchestrator.Result> calculation) {
        return assess(userId, productId, amount, List.of("WOE"), calculation);
    }

    /**
     * 未完成的并发请求抛出异常供调用方重试，不再次执行计算。
     * 计算必须在 120 秒锁租期内完成；超期不发布缓存，后续接入应限制评估耗时。
     * 未提供策略时默认 WOE；显式空集合与 WOE 分开缓存。
     */
    public Lookup assess(Long userId, Long productId, BigDecimal amount, List<String> enabledModels,
                         Supplier<TwoStageRiskOrchestrator.Result> calculation) {
        Objects.requireNonNull(calculation, "calculation 不能为空");
        // 必须在读缓存之前校验策略，避免缓存命中绕过编排层的校验。
        String key = key(userId, productId, amount, normalizeModels(enabledModels));
        String lockKey = key + ":lock";
        TwoStageRiskOrchestrator.Result cached = read(key);
        if (cached != null) return reused(key, cached);

        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, TTL))) {
            cached = read(key);
            if (cached != null) return reused(key, cached);
            log.info("风控去重：相近申请正在评估，未重复计算，key={}", key);
            throw new IllegalStateException("相近申请正在评估，请稍后重试");
        }
        try {
            // 首次读取和拿锁之间，另一请求可能已完成评估。
            cached = read(key);
            if (cached != null) return reused(key, cached);
            log.info("风控去重：开始计算，reused=false，key={}", key);
            TwoStageRiskOrchestrator.Result calculated = Objects.requireNonNull(
                    calculation.get(), "评估结果不能为空");
            if (!"MANUAL_REVIEW".equals(calculated.decision())) {
                String json = objectMapper.writeValueAsString(calculated);
                Long published = redis.execute(PUBLISH, List.of(lockKey, key), token, json,
                        Long.toString(TTL.toSeconds()));
                if (!Long.valueOf(1).equals(published)) {
                    throw new IllegalStateException("评估锁已失效，请重试");
                }
            }
            return new Lookup(calculated, false);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化风控计算结果", exception);
        } finally {
            try {
                redis.execute(RELEASE, List.of(lockKey), token);
            } catch (RuntimeException exception) {
                log.warn("释放风控去重锁失败，将等待租期到期", exception);
            }
        }
    }

    private TwoStageRiskOrchestrator.Result read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, TwoStageRiskOrchestrator.Result.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取风控缓存，请检查策略版本或等待缓存过期", exception);
        }
    }

    private static Lookup reused(String key, TwoStageRiskOrchestrator.Result cached) {
        log.info("风控去重：缓存命中，未重算，reused=true，key={}", key);
        return new Lookup(cached, true);
    }

    private static List<String> normalizeModels(List<String> enabledModels) {
        return EnabledRiskModels.normalize(enabledModels).stream().sorted().toList();
    }

    private String key(Long userId, Long productId, BigDecimal amount, List<String> enabledModels) {
        if (userId == null || userId <= 0 || productId == null || productId <= 0) {
            throw new IllegalArgumentException("去重需要有效的用户主键和产品 ID");
        }
        if (amount == null || amount.signum() <= 0 || bucketSize == null || bucketSize.signum() <= 0) {
            throw new IllegalArgumentException("申请金额及金额桶宽度必须大于 0");
        }
        if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("去重策略版本格式不合法");
        }
        // [0,1000)、[1000,2000)……；使用 BigDecimal 避免浮点边界误差。
        String bucket = amount.divideToIntegralValue(bucketSize).toBigInteger().toString();
        // 与第四周申请卡金额分箱边界一致，避免 50000/50001、200000/200001 同桶跨档复用。
        String amountBand = amount.compareTo(new BigDecimal("50000")) <= 0 ? "LE_50000"
                : amount.compareTo(new BigDecimal("200000")) <= 0 ? "LE_200000" : "GT_200000";
        String models = enabledModels.isEmpty() ? "NONE" : String.join("+", enabledModels);
        // 同一 hash tag 使结果键和锁键在 Redis Cluster 中落在同一槽。
        return "risk:assessment:w7-behavior-v2:" + policyVersion + ":{" + userId + ":" + productId + ":" + bucket
                + ":" + amountBand + ":" + models + "}";
    }
}
