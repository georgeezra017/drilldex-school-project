// src/main/java/com/drilldex/drillbackend/util/TagUtils.java
package com.drilldex.drillbackend.util;

import java.util.*;
import java.util.stream.Collectors;

public class TagUtils {
    public static String normalizeTags(String rawTags) {
        return normalizeTags(rawTags, 12, 24); // defaults: 12 tags max, 24 chars per tag
    }

    public static String normalizeTags(String rawTags, int maxTags, int tagMaxLen) {
        if (rawTags == null || rawTags.isBlank()) return "";

        // remove everything except letters, numbers, comma, and space
        String cleaned = rawTags.replaceAll("[^A-Za-z0-9, ]+", "");

        // split on commas
        String[] parts = cleaned.split(",");
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            String tag = part.trim()
                    .replaceAll("\\s+", " ") // collapse multiple spaces
                    .toLowerCase();

            if (tag.isEmpty()) continue;
            if (tag.length() > tagMaxLen) tag = tag.substring(0, tagMaxLen);

            if (!seen.contains(tag)) {
                seen.add(tag);
                result.add(tag);
                if (result.size() >= maxTags) break;
            }
        }

        return result.stream().collect(Collectors.joining(", "));
    }
}