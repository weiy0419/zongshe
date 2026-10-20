package com.example.zongshe1.modules.crawler.service;

import com.example.zongshe1.modules.risk.datasource.DataSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataCrawlerServiceTest {

    @Mock
    private ExternalDataCacheService cacheService;

    @Mock
    private ResourceLoader resourceLoader;

    @InjectMocks
    private DataCrawlerService dataCrawlerService;

    @Test
    void runFullCrawl_shouldParseDemoHtmlAndSaveCache() {
        org.springframework.core.io.Resource creditRes = new DefaultResourceLoader()
                .getResource("classpath:crawler/demo-credit.html");
        org.springframework.core.io.Resource telecomRes = new DefaultResourceLoader()
                .getResource("classpath:crawler/demo-telecom.html");
        when(resourceLoader.getResource("classpath:crawler/demo-credit.html")).thenReturn(creditRes);
        when(resourceLoader.getResource("classpath:crawler/demo-telecom.html")).thenReturn(telecomRes);
        String dishonestUrl = "classpath:crawler/test-dishonest.html";
        String lprPrimaryUrl = "classpath:crawler/test-lpr-primary.html";
        String lprBackupUrl = "classpath:crawler/test-lpr-backup.html";
        when(resourceLoader.getResource(dishonestUrl)).thenReturn(new ByteArrayResource("""
                <html><body><div class="article-content"><table><tbody>
                <tr><th>失信公示表头</th></tr>
                <tr><td>1</td><td>测试法院</td><td>测试案号001</td><td>张 三</td>
                    <td>TEST-ID-001</td><td>失信被执行人</td><td></td><td></td>
                    <td>2026-10-01</td><td>2027-10-01</td><td>备注</td></tr>
                </tbody></table></div></body></html>
                """.getBytes(StandardCharsets.UTF_8)));
        when(resourceLoader.getResource(lprPrimaryUrl)).thenReturn(new ByteArrayResource("""
                <html><body>
                <div class="article-a-toolbar"><span>2026-10-20</span></div>
                <div id="ewebeditor_content">1年期LPR为3.0%，5年期以上LPR为3.5%。</div>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(dataCrawlerService, "creditUrl", "classpath:crawler/demo-credit.html");
        ReflectionTestUtils.setField(dataCrawlerService, "telecomUrl", "classpath:crawler/demo-telecom.html");
        ReflectionTestUtils.setField(dataCrawlerService, "dishonestUrl", dishonestUrl);
        ReflectionTestUtils.setField(dataCrawlerService, "lprPrimaryUrl", lprPrimaryUrl);
        ReflectionTestUtils.setField(dataCrawlerService, "lprBackupUrl", lprBackupUrl);
        ReflectionTestUtils.setField(dataCrawlerService, "cacheTtlHours", 24);

        Map<String, Object> summary = dataCrawlerService.runFullCrawl();

        assertEquals(10, summary.get("creditRows"));
        assertEquals(10, summary.get("telecomRows"));
        assertEquals(1, summary.get("dishonestRows"));
        assertEquals("CHINAMONEY", summary.get("lprSource"));

        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService, atLeast(20)).save(sourceCaptor.capture(), any(), any(), anyMap(), anyString(), eq(24));
        assertTrue(sourceCaptor.getAllValues().contains(DataSourceType.CREDIT_BUREAU.name()));
        verify(cacheService, times(10)).save(eq(DataSourceType.CREDIT_BUREAU.name()),
                anyString(), eq(""), anyMap(), eq("classpath:crawler/demo-credit.html"), eq(24));
        verify(cacheService, times(10)).save(eq(DataSourceType.TELECOM.name()),
                eq(""), anyString(), anyMap(), eq("classpath:crawler/demo-telecom.html"), eq(24));
        verify(cacheService).saveByLookupKey(DataSourceType.DISHONEST_PUBLIC.name(), "NAME_张三",
                Map.of("dishonestHit", true, "dishonestName", "张 三", "courtName", "测试法院",
                        "caseNumber", "测试案号001", "idCardNumber", "TEST-ID-001",
                        "listType", "失信被执行人", "publishDate", "2026-10-01",
                        "publicEndDate", "2027-10-01", "stale", false),
                dishonestUrl, 24);
        verify(cacheService).saveByLookupKey(DataSourceType.MACRO_LPR.name(), "LPR_CURRENT",
                Map.of("lpr1y", 3.0, "lpr5y", 3.5, "lprPublishDate", "2026-10-20",
                        "source", "CHINAMONEY", "stale", false),
                lprPrimaryUrl, 24);
        verify(resourceLoader, never()).getResource(lprBackupUrl);
        verifyNoMoreInteractions(cacheService);
    }
}
