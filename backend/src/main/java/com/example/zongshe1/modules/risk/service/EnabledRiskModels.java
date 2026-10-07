package com.example.zongshe1.modules.risk.service;

import java.util.List;

/** Snapshot-based risk evaluation. */
public final class EnabledRiskModels {
    private EnabledRiskModels() {}

    public static List<String> normalize(List<String> models) {
        if (models == null) return List.of("WOE");
        for (String model : models) {
            if (!"WOE".equals(model) && !"EXTERNAL".equals(model)) {
                throw new IllegalArgumentException("Unknown credit model: " + model);
            }
        }
        return models.stream().distinct().toList();
    }
}
