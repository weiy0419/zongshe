package com.example.zongshe1.modules.ops.policy;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RiskPolicyUpdateRequest {

    private List<String> enabledModels;

    /**
     * 硬规则不在启用列表里。显式传 false 会被拒绝；不传表示保持必开。
     */
    private Boolean hardRulesEnabled;
}
