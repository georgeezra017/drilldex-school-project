package com.drilldex.drillbackend.subscription.dto;

import java.util.List;

public class SubscriptionResponse {
    public List<Link> links;

    public static class Link {
        public String href;
        public String rel;
        public String method;
    }
}
