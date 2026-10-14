package com.example.zongshe1.modules.ops.cs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsAskServiceTest {

    private final CsAskService service = new CsAskService(new FaqCatalog());

    @Test
    void repaymentQuestionHitsFaq() {
        Map<String, Object> body = service.ask("怎么还款");

        assertEquals(CsAskService.SOURCE_FAQ, body.get("source"));
        assertEquals(Boolean.TRUE, body.get("degraded"));
        assertTrue(String.valueOf(body.get("answer")).contains("还款页"));
    }

    @Test
    void overdueQuestionDoesNotUseRepaymentAnswer() {
        Map<String, Object> body = service.ask("逾期了怎么办");

        assertTrue(String.valueOf(body.get("answer")).contains("罚息"));
        assertFalse(String.valueOf(body.get("answer")).contains("还款页"));
    }

    @Test
    void unknownQuestionStillReturnsFaqShape() {
        Map<String, Object> body = service.ask("今天天气怎么样");

        assertEquals(CsAskService.NO_MATCH_ANSWER, body.get("answer"));
        assertEquals(CsAskService.SOURCE_FAQ, body.get("source"));
        assertEquals(Boolean.TRUE, body.get("degraded"));
    }

    @Test
    void catalogCoversRequiredTopics() {
        List<FaqEntry> entries = new FaqCatalog().entries();

        assertTrue(entries.size() >= 8 && entries.size() <= 15);
        String questions = entries.stream().map(FaqEntry::question).reduce("", (left, right) -> left + right);
        for (String topic : List.of("还款", "逾期", "合同", "额度", "登录")) {
            assertTrue(questions.contains(topic));
        }
    }
}
