package com.example.zongshe1.modules.ops.cache;

import com.github.benmanes.caffeine.cache.Cache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产品读路径：Caffeine(L1) → Redis(L2) → DB。
 * 不改信贷 ProductController / ProductService。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class ProductListCacheFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/products";
    private static final String REDIS_PREFIX = "ops:product:";
    private static final String EMPTY_MARKER = "__EMPTY__";
    private static final byte[] EMPTY_MARKER_BYTES = EMPTY_MARKER.getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_BODY =
            "{\"success\":false,\"message\":\"产品不存在\"}".getBytes(StandardCharsets.UTF_8);
    private static final Pattern DETAIL_PATH = Pattern.compile("^/api/products/(\\d+)$");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ProductCacheProperties props;
    private final Cache<String, byte[]> productListCache;
    private final Cache<String, byte[]> productNullCache;
    private final ProductCacheMetrics metrics;
    private final StringRedisTemplate redis;

    public ProductListCacheFilter(ProductCacheProperties props,
                                  @Qualifier("productListCache") Cache<String, byte[]> productListCache,
                                  @Qualifier("productNullCache") Cache<String, byte[]> productNullCache,
                                  ProductCacheMetrics metrics,
                                  StringRedisTemplate redis) {
        this.props = props;
        this.productListCache = productListCache;
        this.productNullCache = productNullCache;
        this.metrics = metrics;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!props.isEnabled() || !isProductApi(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        String uri = request.getRequestURI();
        boolean list = isProductList(uri);
        boolean detail = isProductDetail(uri);

        if ("GET".equalsIgnoreCase(method) && (list || detail)) {
            handleGet(request, response, filterChain, detail);
            return;
        }

        if (WRITE_METHODS.contains(method.toUpperCase())) {
            handleWrite(request, response, filterChain);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void handleGet(HttpServletRequest request,
                           HttpServletResponse response,
                           FilterChain filterChain,
                           boolean detail) throws ServletException, IOException {
        String l1Key = cacheKey(request);
        String redisKey = redisKey(request, detail);

        if (detail && productNullCache.getIfPresent(l1Key) != null) {
            metrics.l1Hit();
            log.info("product-cache L1_HIT empty key={} l1={} l2={} db={}",
                    l1Key, metrics.l1Hits(), metrics.l2Hits(), metrics.dbLoads());
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, EMPTY_BODY);
            return;
        }

        byte[] cached = productListCache.getIfPresent(l1Key);
        if (cached != null) {
            metrics.l1Hit();
            log.info("product-cache L1_HIT key={} l1={} l2={} db={}",
                    l1Key, metrics.l1Hits(), metrics.l2Hits(), metrics.dbLoads());
            writeJson(response, HttpServletResponse.SC_OK, cached);
            return;
        }

        String fromRedis = redisGet(redisKey);
        if (EMPTY_MARKER.equals(fromRedis)) {
            metrics.l2Hit();
            productNullCache.put(l1Key, EMPTY_MARKER_BYTES);
            log.info("product-cache L2_HIT empty key={} l1={} l2={} db={}",
                    redisKey, metrics.l1Hits(), metrics.l2Hits(), metrics.dbLoads());
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, EMPTY_BODY);
            return;
        }
        if (fromRedis != null && !fromRedis.isEmpty()) {
            metrics.l2Hit();
            byte[] body = fromRedis.getBytes(StandardCharsets.UTF_8);
            productListCache.put(l1Key, body);
            log.info("product-cache L2_HIT key={} l1={} l2={} db={}",
                    redisKey, metrics.l1Hits(), metrics.l2Hits(), metrics.dbLoads());
            writeJson(response, HttpServletResponse.SC_OK, body);
            return;
        }

        metrics.dbLoad();
        log.info("product-cache DB_LOAD key={} l1={} l2={} db={}",
                l1Key, metrics.l1Hits(), metrics.l2Hits(), metrics.dbLoads());
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapped);
        int status = wrapped.getStatus();
        byte[] body = wrapped.getContentAsByteArray();
        if (status == HttpServletResponse.SC_OK && body.length > 0) {
            productListCache.put(l1Key, body);
            redisSet(redisKey, new String(body, StandardCharsets.UTF_8), props.getL2TtlSeconds());
        } else if (detail && status == HttpServletResponse.SC_BAD_REQUEST) {
            productNullCache.put(l1Key, EMPTY_MARKER_BYTES);
            redisSet(redisKey, EMPTY_MARKER, props.getNullTtlSeconds());
            log.info("product-cache EMPTY_CACHED key={}", redisKey);
        }
        wrapped.copyBodyToResponse();
    }

    private void handleWrite(HttpServletRequest request,
                             HttpServletResponse response,
                             FilterChain filterChain) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapped);
        int status = wrapped.getStatus();
        if (status >= 200 && status < 300) {
            productListCache.invalidateAll();
            productNullCache.invalidateAll();
            redisDeleteProductKeys();
            metrics.invalidate();
            log.info("product-cache INVALIDATE after {} {} status={}",
                    request.getMethod(), request.getRequestURI(), status);
        }
        wrapped.copyBodyToResponse();
    }

    private void writeJson(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body);
    }

    private String redisKey(HttpServletRequest request, boolean detail) {
        if (detail) {
            Matcher matcher = DETAIL_PATH.matcher(request.getRequestURI());
            if (matcher.matches()) {
                return REDIS_PREFIX + "id:" + matcher.group(1);
            }
        }
        return REDIS_PREFIX + "list:" + cacheKey(request);
    }

    private String redisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("product-cache Redis GET failed key={}: {}", key, ex.getMessage());
            return null;
        }
    }

    private void redisSet(String key, String value, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("product-cache Redis SET failed key={}: {}", key, ex.getMessage());
        }
    }

    private void redisDeleteProductKeys() {
        try {
            Set<String> keys = redis.keys(REDIS_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception ex) {
            log.warn("product-cache Redis DELETE failed: {}", ex.getMessage());
        }
    }

    private boolean isProductApi(String uri) {
        return uri != null && (PREFIX.equals(uri) || uri.startsWith(PREFIX + "/"));
    }

    private boolean isProductList(String uri) {
        return PREFIX.equals(uri);
    }

    private boolean isProductDetail(String uri) {
        return uri != null && DETAIL_PATH.matcher(uri).matches();
    }

    private String cacheKey(HttpServletRequest request) {
        String qs = request.getQueryString();
        return request.getMethod() + "|" + request.getRequestURI() + "|" + (qs == null ? "" : qs);
    }
}
