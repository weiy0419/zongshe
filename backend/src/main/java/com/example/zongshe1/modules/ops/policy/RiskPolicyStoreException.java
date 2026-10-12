package com.example.zongshe1.modules.ops.policy;

/**
 * 策略存储读失败时禁止写入，避免用猜出来的版本覆盖。
 */
public class RiskPolicyStoreException extends RuntimeException {

    public RiskPolicyStoreException(String message) {
        super(message);
    }

    public RiskPolicyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
