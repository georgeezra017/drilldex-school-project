// com.drilldex.drillbackend.util.TimeAgo
package com.drilldex.drillbackend.util;

import java.time.*;
import java.time.temporal.ChronoUnit;

public final class TimeAgo {
    private TimeAgo() {}
    public static String of(Instant created) {
        if (created == null) return "";
        Instant now = Instant.now();
        long secs = ChronoUnit.SECONDS.between(created, now);
        if (secs < 60)  return "just now";
        long mins = secs / 60;
        if (mins < 60)  return mins + "m";
        long hours = mins / 60;
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        if (days < 7)   return days + "d";
        long weeks = days / 7;
        if (weeks < 4)  return weeks + "w";
        long months = days / 30;
        if (months < 12) return months + "mo";
        long years = days / 365;
        return years + "y";
    }
}