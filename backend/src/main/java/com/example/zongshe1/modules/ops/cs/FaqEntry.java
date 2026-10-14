package com.example.zongshe1.modules.ops.cs;

import java.util.List;

public record FaqEntry(String id, String question, String answer, List<String> keywords) {
}
