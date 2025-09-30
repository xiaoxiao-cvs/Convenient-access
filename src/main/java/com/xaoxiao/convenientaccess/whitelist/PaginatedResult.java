package com.xaoxiao.convenientaccess.whitelist;

import java.util.List;

/**
 * 分页结果类
 */
public class PaginatedResult<T> {
    private final List<T> items;
    private final int page;
    private final int size;
    private final long total;
    private final int pages;
    
    public PaginatedResult(List<T> items, int page, int size, long total) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.total = total;
        this.pages = (int) Math.ceil((double) total / size);
    }
    
    public List<T> getItems() {
        return items;
    }
    
    public int getPage() {
        return page;
    }
    
    public int getSize() {
        return size;
    }
    
    public long getTotal() {
        return total;
    }
    
    public int getPages() {
        return pages;
    }
    
    public boolean hasNext() {
        return page < pages;
    }
    
    public boolean hasPrevious() {
        return page > 1;
    }
    
    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }
    
    @Override
    public String toString() {
        return "PaginatedResult{" +
                "page=" + page +
                ", size=" + size +
                ", total=" + total +
                ", pages=" + pages +
                ", itemCount=" + (items != null ? items.size() : 0) +
                '}';
    }
}