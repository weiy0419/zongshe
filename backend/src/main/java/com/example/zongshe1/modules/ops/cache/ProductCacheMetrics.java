package com.example.zongshe1.modules.ops.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class ProductCacheMetrics {
    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong dbLoads = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    public void l1Hit() {
        l1Hits.incrementAndGet();
    }

    public void l2Hit() {
        l2Hits.incrementAndGet();
    }

    public void dbLoad() {
        dbLoads.incrementAndGet();
    }

    public void invalidate() {
        invalidations.incrementAndGet();
    }

    public long l1Hits() {
        return l1Hits.get();
    }

    public long l2Hits() {
        return l2Hits.get();
    }

    public long dbLoads() {
        return dbLoads.get();
    }

    public long invalidations() {
        return invalidations.get();
    }
}