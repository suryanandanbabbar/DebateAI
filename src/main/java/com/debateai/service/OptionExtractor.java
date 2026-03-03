package com.debateai.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class OptionExtractor {

    private static final Pattern OR_TOKEN = Pattern.compile("(?i)\\bor\\b");
    private static final Pattern SPLIT_PATTERN = Pattern.compile("(?i)\\s+or\\s+");
    private static final Pattern PREFERENCE_PREFIX = Pattern.compile(
            "(?i)^.*\\b(?:prefer|choose|pick|select|use|adopt|recommend|go\\s+with|evaluate|compare)\\s+"
    );

    private OptionExtractor() {
    }

    public static List<String> extractOptions(String topic) {
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("Malformed topic: must not be blank");
        }

        String cleanedTopic = topic.replace("?", " ").trim();
        long orCount = countOrTokens(cleanedTopic);
        if (orCount != 1L) {
            throw new IllegalArgumentException("Malformed topic: expected exactly one 'or'");
        }

        String[] parts = SPLIT_PATTERN.split(cleanedTopic, 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed topic: unable to split options");
        }

        String left = extractFinalSubjectTokens(parts[0]);
        String right = extractFinalSubjectTokens(parts[1]);

        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            throw new IllegalArgumentException("Malformed topic: extracted options are blank");
        }
        if (left.equalsIgnoreCase(right)) {
            throw new IllegalArgumentException("Malformed topic: options must be distinct");
        }

        return List.of(left, right);
    }

    private static long countOrTokens(String text) {
        Matcher matcher = OR_TOKEN.matcher(text);
        long count = 0L;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String extractFinalSubjectTokens(String segment) {
        String normalized = segment.replace("?", " ")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }

        String withoutPrefix = PREFERENCE_PREFIX.matcher(normalized).replaceFirst("");

        if (withoutPrefix.equals(normalized) && normalized.contains(",")) {
            withoutPrefix = normalized.substring(normalized.lastIndexOf(',') + 1).trim();
        }

        return withoutPrefix
                .replaceAll("^[\"'`]+|[\"'`]+$", "")
                .replaceAll("[.!]+$", "")
                .trim();
    }
}
