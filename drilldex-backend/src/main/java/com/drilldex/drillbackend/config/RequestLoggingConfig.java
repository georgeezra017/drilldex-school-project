package com.drilldex.drillbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

@Configuration
public class RequestLoggingConfig {

    @Bean
    public CommonsRequestLoggingFilter logFilter() {
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
        filter.setIncludeClientInfo(true);
        filter.setIncludeQueryString(true);
        filter.setIncludePayload(true);
        filter.setIncludeHeaders(true); // log headers
        filter.setMaxPayloadLength(1000); // limit payload length
        return filter;
    }
}
