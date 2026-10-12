package com.example.zongshe1.modules.ops.policy;

import com.example.zongshe1.common.exception.GlobalExceptionHandler;
import com.example.zongshe1.common.util.JwtUtil;
import com.example.zongshe1.modules.ops.security.BearerRoleResolver;
import com.example.zongshe1.modules.ops.policy.RiskPolicyStore.Loaded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RiskPolicyControllerTest {

    @Mock
    private RiskPolicyStore store;

    @Mock
    private JwtUtil jwtUtil;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RiskPolicyService service = new RiskPolicyService(store);
        mvc = MockMvcBuilders.standaloneSetup(
                new RiskPolicyController(service, new BearerRoleResolver(jwtUtil)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/ops/risk-policy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("需要登录"));

        verify(store, never()).load();
    }

    @Test
    void userCanReadPolicy() throws Exception {
        when(jwtUtil.validateToken("user-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("user-token")).thenReturn("13800138000");
        when(store.load()).thenReturn(Loaded.missing());

        mvc.perform(get("/api/ops/risk-policy").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledModels[0]").value("WOE"))
                .andExpect(jsonPath("$.policyVersion").value(1))
                .andExpect(jsonPath("$.fallback").doesNotExist());
    }

    @Test
    void userCannotSave() throws Exception {
        when(jwtUtil.validateToken("user-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("user-token")).thenReturn("13800138000");

        mvc.perform(put("/api/ops/risk-policy")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledModels\":[\"EXTERNAL\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("需要管理员权限"));

        verify(store, never()).load();
    }

    @Test
    void adminSaveReturnsNumericVersion() throws Exception {
        when(jwtUtil.validateToken("admin-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("admin-token")).thenReturn("admin");
        when(store.load()).thenReturn(Loaded.found(List.of("WOE"), 1));

        mvc.perform(put("/api/ops/risk-policy")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledModels\":[\"external\",\"WOE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledModels[0]").value("WOE"))
                .andExpect(jsonPath("$.enabledModels[1]").value("EXTERNAL"))
                .andExpect(jsonPath("$.policyVersion").value(2))
                .andExpect(jsonPath("$.policyVersion").isNumber());

        verify(store).save(List.of("WOE", "EXTERNAL"), 2);
    }

    @Test
    void hardRuleLockIsBadRequest() throws Exception {
        when(jwtUtil.validateToken("admin-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("admin-token")).thenReturn("admin");

        mvc.perform(put("/api/ops/risk-policy")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledModels\":[\"WOE\"],\"hardRulesEnabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HARD_RULE_LOCKED"));

        verify(store, never()).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void storeOutageOnSaveIsServiceUnavailable() throws Exception {
        when(jwtUtil.validateToken("admin-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("admin-token")).thenReturn("admin");
        when(store.load()).thenReturn(Loaded.unavailable());

        mvc.perform(put("/api/ops/risk-policy")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabledModels\":[\"WOE\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("POLICY_STORE_UNAVAILABLE"));
    }
}
