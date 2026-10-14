package com.example.zongshe1.modules.ops.cs;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本周只做关键词匹配。没有模型密钥时也走这里，回答保持 HTTP 200。
 */
@Service
@RequiredArgsConstructor
public class CsAskService {

    static final String SOURCE_FAQ = "FAQ";
    static final String NO_MATCH_ANSWER = "没有找到对应的解答。可以改问还款、逾期、合同、额度或登录。";

    private final FaqCatalog catalog;

    public Map<String, Object> faq() {
        List<Map<String, String>> items = catalog.entries().stream()
                .map(this::toItem)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return body;
    }

    public Map<String, Object> ask(String question) {
        String normalized = normalize(question);
        FaqEntry best = null;
        int bestScore = 0;
        for (FaqEntry entry : catalog.entries()) {
            int score = score(normalized, entry);
            if (score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        String answer = best == null ? NO_MATCH_ANSWER : best.answer();
        return answerBody(answer);
    }

    private int score(String normalizedQuestion, FaqEntry entry) {
        int score = 0;
        if (normalizedQuestion.contains(normalize(entry.question()))) {
            score += entry.question().length();
        }
        for (String keyword : entry.keywords()) {
            String normalizedKeyword = normalize(keyword);
            if (!normalizedKeyword.isEmpty() && normalizedQuestion.contains(normalizedKeyword)) {
                score += normalizedKeyword.length();
            }
        }
        return score;
    }

    private Map<String, String> toItem(FaqEntry entry) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("id", entry.id());
        item.put("question", entry.question());
        item.put("answer", entry.answer());
        return item;
    }

    private static Map<String, Object> answerBody(String answer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", answer);
        body.put("source", SOURCE_FAQ);
        body.put("degraded", Boolean.TRUE);
        return body;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
    }
}
