package com.example.zongshe1.modules.crawler.service;

import com.example.zongshe1.modules.risk.datasource.DataSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

/**
 * 演示级数据爬取：解析 HTML 表格写入外部数据缓存，供风控适配器融合使用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataCrawlerService {

    private final ExternalDataCacheService cacheService;
    private final ResourceLoader resourceLoader;

    @Value("${crawler.credit-bureau.url:classpath:crawler/demo-credit.html}")
    private String creditUrl;

    @Value("${crawler.telecom.url:classpath:crawler/demo-telecom.html}")
    private String telecomUrl;

    @Value("${crawler.cache-ttl-hours:24}")
    private int cacheTtlHours;

    @Value("${crawler.dishonest.url}")
    private String dishonestUrl;

    @Value("${crawler.lpr.primary-url}")
    private String lprPrimaryUrl;

    @Value("${crawler.lpr.backup-url}")
    private String lprBackupUrl;

    @Value("${crawler.external.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${crawler.external.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Value("${crawler.external.user-agent:ZongsheIII-RiskCrawler/1.0}")
    private String externalUserAgent;

    private static final Pattern LPR_1Y_PATTERN =
            Pattern.compile("1\\s*年\\s*期\\s*LPR\\s*为\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%");

    private static final Pattern LPR_5Y_PATTERN =
            Pattern.compile("5\\s*年\\s*期\\s*以上\\s*LPR\\s*为\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%");

    private  HttpClient httpClient;

    @PostConstruct
    public void initHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Transactional
    public Map<String, Object> runFullCrawl() {
        Map<String, Object> summary = new HashMap<>();
        try {
            summary.put("creditRows", crawlCreditBureau());
            summary.put("telecomRows", crawlTelecom());
            summary.put("dishonestRows", crawlDishonestPublic());
            summary.put("lprSource", crawlLpr());
            summary.put("message", "爬取完成，数据已写入 t_external_data_cache");
        } catch (Exception e) {
            log.error("数据爬取失败", e);
            throw new IllegalStateException("数据爬取失败：" + e.getMessage(), e);
        }
        log.info("数据爬取完成：{}", summary);
        return summary;
    }

    private int crawlCreditBureau() throws Exception {
        Document doc = loadDocument(creditUrl);
        Elements rows = doc.select("table#credit-data tbody tr");
        int count = 0;
        for (Element row : rows) {
            Elements cols = row.select("td");
            if (cols.size() < 3) continue;
            String suffix = cols.get(0).text().trim();
            int overdue = Integer.parseInt(cols.get(1).text().trim());
            int queryCount = Integer.parseInt(cols.get(2).text().trim());
            Map<String, Object> payload = Map.of(
                    "credit_overdue_count", overdue,
                    "credit_query_count_30d", queryCount,
                    "id_suffix", suffix
            );
            cacheService.save(DataSourceType.CREDIT_BUREAU.name(), "SUFFIX_" + suffix, "",
                    payload, creditUrl, cacheTtlHours);
            count++;
        }
        return count;
    }

    private int crawlTelecom() throws Exception {
        Document doc = loadDocument(telecomUrl);
        Elements rows = doc.select("table#telecom-data tbody tr");
        int count = 0;
        for (Element row : rows) {
            Elements cols = row.select("td");
            if (cols.size() < 3) continue;
            String suffix = cols.get(0).text().trim();
            int months = Integer.parseInt(cols.get(1).text().trim());
            boolean verified = Boolean.parseBoolean(cols.get(2).text().trim());
            Map<String, Object> payload = Map.of(
                    "telecom_online_months", months,
                    "telecom_real_name_verified", verified,
                    "phone_suffix", suffix
            );
            cacheService.save(DataSourceType.TELECOM.name(), "", "SUFFIX_" + suffix,
                    payload, telecomUrl, cacheTtlHours);
            count++;
        }
        return count;
    }

    private Document loadDocument(String url) throws Exception {
        if (url.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(url);
            try (InputStream in = resource.getInputStream()) {
                return Jsoup.parse(in, "UTF-8", url);
            }
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("User-Agent", externalUserAgent)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("爬取失败 HTTP " + response.statusCode() + " url=" + url);
        }
        return Jsoup.parse(response.body(), url);
    }

    private int crawlDishonestPublic() throws Exception {
        Document doc = loadDocument(dishonestUrl);

        Elements rows = doc.select(".article-content table tbody tr");
        int count = 0;

        // 第 0 行是表头，从第 1 行开始才是真正的数据
        for (int i = 1; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements cols = row.select("td");

            // 页面正常情况下有 11 列
            if (cols.size() < 10) {
                continue;
            }

            String courtName = cols.get(1).text().trim();
            String caseNumber = cols.get(2).text().trim();
            String dishonestName = cols.get(3).text().trim();
            String idCardNumber = cols.get(4).text().trim();
            String listType = cols.get(5).text().trim();
            String dishonestDate = cols.get(8).text().trim();
            String publicEndDate = cols.get(9).text().trim();

            if (dishonestName.isBlank()) {
                continue;
            }

            String normalizedName = normalizeName(dishonestName);
            String lookupKey = "NAME_" + normalizedName;

            Map<String, Object> payload = new HashMap<>();
            payload.put("dishonestHit", true);
            payload.put("dishonestName", dishonestName);
            payload.put("courtName", courtName);
            payload.put("caseNumber", caseNumber);
            payload.put("idCardNumber", idCardNumber);
            payload.put("listType", listType);
            payload.put("publishDate", dishonestDate);
            payload.put("publicEndDate", publicEndDate);
            payload.put("stale", false);

            cacheService.saveByLookupKey(
                    DataSourceType.DISHONEST_PUBLIC.name(),
                    lookupKey,
                    payload,
                    dishonestUrl,
                    cacheTtlHours
            );

            count++;
        }

        log.info("失信公告抓取完成，共写入 {} 条记录", count);
        return count;
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }

        return name
                .trim()
                .replace(" ", "")
                .replace("\u3000", "")
                .toUpperCase();
    }

    private String crawlLpr() throws Exception {

        // 1. 优先抓中国货币网
        try {
            Document doc = loadDocument(lprPrimaryUrl);

            String text = doc.select("#ewebeditor_content").text();

            Matcher oneYearMatcher = LPR_1Y_PATTERN.matcher(text);
            Matcher fiveYearMatcher = LPR_5Y_PATTERN.matcher(text);

            if (!oneYearMatcher.find() || !fiveYearMatcher.find()) {
                throw new IllegalStateException("中国货币网 LPR 页面解析失败");
            }

            double lpr1y = Double.parseDouble(oneYearMatcher.group(1));
            double lpr5y = Double.parseDouble(fiveYearMatcher.group(1));

            String publishDate = doc
                    .select(".article-a-toolbar span")
                    .stream()
                    .map(Element::text)
                    .filter(value -> value.matches("\\d{4}-\\d{2}-\\d{2}.*"))
                    .findFirst()
                    .orElse("");

            saveLprPayload(
                    lpr1y,
                    lpr5y,
                    publishDate,
                    "CHINAMONEY",
                    lprPrimaryUrl,
                    false
            );

            log.info("LPR主源抓取成功：1Y={}%, 5Y={}%", lpr1y, lpr5y);

            return "CHINAMONEY";

        } catch (Exception primaryException) {
            log.warn("中国货币网LPR抓取失败，准备切换广发银行备用源：{}",
                    primaryException.getMessage());
        }

        // 2. 主源失败后抓广发银行
        try {
            Document doc = loadDocument(lprBackupUrl);

            Elements periods = doc.select(".lprCon ul.b2");

            if (periods.isEmpty()) {
                throw new IllegalStateException("广发银行 LPR 页面未找到利率数据");
            }

            // 页面可能同时有多个月份，取最后一个即最新一期
            Element latest = periods.last();
            Elements items = latest.select("li");

            if (items.size() < 3) {
                throw new IllegalStateException("广发银行 LPR 数据列不足");
            }

            String publishDate = items.get(0)
                    .text()
                    .replace("LPR", "")
                    .trim();

            double lpr1y = Double.parseDouble(
                    items.get(1).text().trim()
            );

            double lpr5y = Double.parseDouble(
                    items.get(2).text().trim()
            );

            saveLprPayload(
                    lpr1y,
                    lpr5y,
                    publishDate,
                    "CGB",
                    lprBackupUrl,
                    false
            );

            log.info("LPR备用源抓取成功：1Y={}%, 5Y={}%", lpr1y, lpr5y);

            return "CGB";

        } catch (Exception backupException) {
            log.warn("广发银行LPR抓取失败，准备使用最近一次成功缓存：{}",
                    backupException.getMessage());
        }

        // 3. 两个真实数据源都失败，使用最近一次成功值
        Optional<Map<String, Object>> latestCache =
                cacheService.getLatestPayload(
                        DataSourceType.MACRO_LPR.name(),
                        "LPR_CURRENT"
                );

        if (latestCache.isPresent()) {
            log.warn("LPR真实数据源均不可用，使用最近一次成功缓存，stale=true");
            return "CACHE_STALE";
        }

        throw new IllegalStateException(
                "LPR主源、备用源均抓取失败，并且没有历史缓存可用"
        );
    }

    private void saveLprPayload(double lpr1y,
                                double lpr5y,
                                String publishDate,
                                String source,
                                String sourceUrl,
                                boolean stale) {

        Map<String, Object> payload = new HashMap<>();

        payload.put("lpr1y", lpr1y);
        payload.put("lpr5y", lpr5y);
        payload.put("lprPublishDate", publishDate);
        payload.put("source", source);
        payload.put("stale", stale);

        cacheService.saveByLookupKey(
                DataSourceType.MACRO_LPR.name(),
                "LPR_CURRENT",
                payload,
                sourceUrl,
                cacheTtlHours
        );
    }
}
