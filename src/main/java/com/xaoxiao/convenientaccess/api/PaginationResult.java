package com.xaoxiao.convenientaccess.api;

import java.util.List;

/**
 * 分页结果类
 */
public class PaginationResult<T> {
    private List<T> items;
    private Pagination pagination;
    
    public PaginationResult() {}
    
    public PaginationResult(List<T> items, int page, int size, long total) {
        this.items = items;
        this.pagination = new Pagination(page, size, total);
    }
    
    public List<T> getItems() {
        return items;
    }
    
    public void setItems(List<T> items) {
        this.items = items;
    }
    
    public Pagination getPagination() {
        return pagination;
    }
    
    public void setPagination(Pagination pagination) {
        this.pagination = pagination;
    }
    
    /**
     * 分页信息类
     */
    public static class Pagination {
        private int page;
        private int size;
        private long total;
        private int pages;
        
        public Pagination() {}
        
        public Pagination(int page, int size, long total) {
            this.page = page;
            this.size = size;
            this.total = total;
            this.pages = (int) Math.ceil((double) total / size);
        }
        
        public int getPage() {
            return page;
        }
        
        public void setPage(int page) {
            this.page = page;
        }
        
        public int getSize() {
            return size;
        }
        
        public void setSize(int size) {
            this.size = size;
        }
        
        public long getTotal() {
            return total;
        }
        
        public void setTotal(long total) {
            this.total = total;
        }
        
        public int getPages() {
            return pages;
        }
        
        public void setPages(int pages) {
            this.pages = pages;
        }
    }
}