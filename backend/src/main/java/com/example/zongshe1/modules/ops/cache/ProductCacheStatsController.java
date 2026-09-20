package com.example.zongshe1.modules.ops.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class ProductCacheStatsController {

    private final ProductCacheMetrics metrics;

    @GetMapping("/cache-stats")
    public Map<String, Long> cacheStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("l1Hits", metrics.l1Hits());
        stats.put("l2Hits", metrics.l2Hits());
        stats.put("dbLoads", metrics.dbLoads());
        return stats;
    }
}
