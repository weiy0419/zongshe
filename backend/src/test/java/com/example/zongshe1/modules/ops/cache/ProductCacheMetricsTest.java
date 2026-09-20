package com.example.zongshe1.modules.ops.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductCacheMetricsTest {

    @Test
    void countersIncrementIndependently() {
        ProductCacheMetrics metrics = new ProductCacheMetrics();

        metrics.l1Hit();
        metrics.l1Hit();
        metrics.l2Hit();
        metrics.dbLoad();

        assertEquals(2, metrics.l1Hits());
        assertEquals(1, metrics.l2Hits());
        assertEquals(1, metrics.dbLoads());
    }

    @Test
    void statsEndpointExposesPlanFieldNames() {
        ProductCacheMetrics metrics = new ProductCacheMetrics();
        metrics.l1Hit();
        metrics.dbLoad();

        Map<String, Long> stats = new ProductCacheStatsController(metrics).cacheStats();

        assertEquals(1L, stats.get("l1Hits"));
        assertEquals(0L, stats.get("l2Hits"));
        assertEquals(1L, stats.get("dbLoads"));
    }

    @Test
    void twoCaffeineCachesDoNotShareEntries() {
        ProductCacheProperties props = new ProductCacheProperties();
        ProductCacheConfig config = new ProductCacheConfig();
        var listCache = config.productListCache(props);
        var nullCache = config.productNullCache(props);

        listCache.put("GET|/api/products|", "list".getBytes());
        nullCache.put("GET|/api/products/999999|", "__EMPTY__".getBytes());

        assertNotNull(listCache.getIfPresent("GET|/api/products|"));
        assertNull(nullCache.getIfPresent("GET|/api/products|"));
        assertNotNull(nullCache.getIfPresent("GET|/api/products/999999|"));
        assertNull(listCache.getIfPresent("GET|/api/products/999999|"));
    }
}
