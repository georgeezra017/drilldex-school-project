package com.drilldex.drillbackend.chat;

import java.time.Instant;

public interface ThreadRow {
    Long getPartnerId();
    Instant getLastTime();
    String getLastContent();
}