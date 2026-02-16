package com.drilldex.drillbackend.shared;

import java.util.List;

public class PaginatedResponse<T> {

    private List<T> items;
    private int totalItems;
    private int page;
    private int limit;

    public PaginatedResponse(List<T> items, int totalItems, int page, int limit) {
        this.items = items;
        this.totalItems = totalItems;
        this.page = page;
        this.limit = limit;
    }

    public List<T> getItems() {
        return items;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getPage() {
        return page;
    }

    public int getLimit() {
        return limit;
    }

    public int getTotalPages() {
        return (int) Math.ceil((double) totalItems / limit);
    }
}