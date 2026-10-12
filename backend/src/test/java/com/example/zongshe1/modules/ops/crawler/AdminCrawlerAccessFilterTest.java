package com.example.zongshe1.modules.ops.crawler;

import com.example.zongshe1.common.util.JwtUtil;
import com.example.zongshe1.modules.crawler.controller.AdminCrawlerController;
import com.example.zongshe1.modules.crawler.service.DataCrawlerService;
import com.example.zongshe1.modules.ops.security.BearerRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminCrawlerAccessFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private DataCrawlerService crawler;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AdminCrawlerAccessFilter filter = new AdminCrawlerAccessFilter(new BearerRoleResolver(jwtUtil));
        mvc = MockMvcBuilders.standaloneSetup(new AdminCrawlerController(crawler))
                .addFilters(filter)
                .build();
    }

    @Test
    void missingTokenDoesNotCrawl() throws Exception {
        mvc.perform(post("/api/admin/crawler/run"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("需要登录"));

        verify(crawler, never()).runFullCrawl();
    }

    @Test
    void userTokenDoesNotCrawl() throws Exception {
        when(jwtUtil.validateToken("user-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("user-token")).thenReturn("13800138000");

        mvc.perform(post("/api/admin/crawler/run")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("需要管理员权限"));

        verify(crawler, never()).runFullCrawl();
    }

    @Test
    void adminReachesExistingCrawlResponse() throws Exception {
        when(jwtUtil.validateToken("admin-token")).thenReturn(true);
        when(jwtUtil.getRoleFromToken("admin-token")).thenReturn("admin");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("message", "爬取完成，数据已写入 t_external_data_cache");
        when(crawler.runFullCrawl()).thenReturn(summary);

        mvc.perform(post("/api/admin/crawler/run")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value("爬取完成，数据已写入 t_external_data_cache"));

        verify(crawler).runFullCrawl();
    }

    @Test
    void otherRequestsAreNotBlockedByThisFilter() throws Exception {
        mvc.perform(get("/api/admin/crawler/run"))
                .andExpect(status().isMethodNotAllowed());

        verify(crawler, never()).runFullCrawl();
    }
}
