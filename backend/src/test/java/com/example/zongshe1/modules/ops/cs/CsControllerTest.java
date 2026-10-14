package com.example.zongshe1.modules.ops.cs;

import com.example.zongshe1.common.util.JwtUtil;
import com.example.zongshe1.modules.ops.security.BearerRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CsControllerTest {

    @Mock
    private JwtUtil jwtUtil;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CsAskService service = new CsAskService(new FaqCatalog());
        mvc = MockMvcBuilders.standaloneSetup(new CsController(service, new BearerRoleResolver(jwtUtil)))
                .build();
    }

    @Test
    void askWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/ops/cs/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"怎么还款\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("需要登录"));
    }

    @Test
    void loggedInUserGetsFaqAnswer() throws Exception {
        when(jwtUtil.validateToken("user-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("user-token")).thenReturn("13800138000");

        mvc.perform(post("/api/ops/cs/ask")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"怎么还款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FAQ"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.answer").isNotEmpty());
    }

    @Test
    void blankQuestionIsBadRequest() throws Exception {
        when(jwtUtil.validateToken("user-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("user-token")).thenReturn("13800138000");

        mvc.perform(post("/api/ops/cs/ask")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void faqListRequiresLogin() throws Exception {
        when(jwtUtil.validateToken("admin-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("admin-token")).thenReturn("admin");

        mvc.perform(get("/api/ops/cs/faq").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].question").isNotEmpty())
                .andExpect(jsonPath("$.items[0].answer").isNotEmpty());
    }
}
