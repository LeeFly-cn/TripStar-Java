package com.zkry.ai.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class LlmJsonExtractor {

    private LlmJsonExtractor() {
    }

    public static Optional<String> extractJsonObject(String text) {
        return extractJsonObjectCandidates(text).stream().findFirst();
    }

    public static Optional<String> extractJsonArray(String text) {
        return extractJsonArrayCandidates(text).stream().findFirst();
    }

    public static List<String> extractJsonObjectCandidates(String text) {
        return extractCandidates(text, '{', '}');
    }

    public static List<String> extractJsonArrayCandidates(String text) {
        return extractCandidates(text, '[', ']');
    }

    private static List<String> extractCandidates(String text, char open, char close) {
        Optional<String> balanced = extractBalanced(text, open, close);
        if (balanced.isEmpty()) {
            return List.of();
        }

        String candidate = balanced.get();
        Set<String> candidates = new LinkedHashSet<>();
        addIfPresent(candidates, candidate);
        addIfPresent(candidates, removeTrailingCommas(candidate));
        addIfPresent(candidates, closeMissingJson(candidate));
        addIfPresent(candidates, removeTrailingCommas(closeMissingJson(candidate)));
        addIfPresent(candidates, normalizeCommonLlmChars(candidate));
        addIfPresent(candidates, removeTrailingCommas(closeMissingJson(normalizeCommonLlmChars(candidate))));
        return new ArrayList<>(candidates);
    }

    private static Optional<String> extractBalanced(String text, char open, char close) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String cleaned = normalizeCommonLlmChars(stripMarkdownFence(text.trim()));
        int start = cleaned.indexOf(open);
        if (start < 0) {
            return Optional.empty();
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return Optional.of(cleaned.substring(start, i + 1));
                }
            }
        }

        // 截断时先返回从首个 JSON 起始符到末尾，让上层决定是否做 LLM 修复。
        return Optional.of(cleaned.substring(start));
    }

    private static String normalizeCommonLlmChars(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text
            .replace('\uFEFF', ' ')
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    private static String removeTrailingCommas(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                result.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                result.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '"') {
                result.append(ch);
                inString = !inString;
                continue;
            }
            if (!inString && ch == ',') {
                int next = nextNonWhitespace(text, i + 1);
                if (next >= 0 && (text.charAt(next) == '}' || text.charAt(next) == ']')) {
                    continue;
                }
            }
            result.append(ch);
        }
        return result.toString();
    }

    private static String closeMissingJson(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        ArrayDeque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                stack.push('}');
            } else if (ch == '[') {
                stack.push(']');
            } else if ((ch == '}' || ch == ']') && !stack.isEmpty() && stack.peek() == ch) {
                stack.pop();
            }
        }

        if (stack.isEmpty() && !inString) {
            return text;
        }
        StringBuilder repaired = new StringBuilder(text);
        if (inString) {
            repaired.append('"');
        }
        while (!stack.isEmpty()) {
            repaired.append(stack.pop());
        }
        return repaired.toString();
    }

    private static int nextNonWhitespace(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void addIfPresent(Set<String> candidates, String text) {
        if (text != null && !text.isBlank()) {
            candidates.add(text.trim());
        }
    }

    private static String stripMarkdownFence(String text) {
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                return text.substring(start + 1, end).trim();
            }
        }
        return text;
    }
}
