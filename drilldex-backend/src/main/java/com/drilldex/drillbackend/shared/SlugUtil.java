// src/main/java/com/drilldex/drillbackend/shared/SlugUtil.java
package com.drilldex.drillbackend.shared;

import java.text.Normalizer;

public final class SlugUtil {
    private SlugUtil() {}

    public static String toSlug(String input) {
        if (input == null) return "";
        String n = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String s = n.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.length() == 0 ? "item" : s;
    }
}