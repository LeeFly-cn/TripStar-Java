package com.zkry.common.core.domain;

public class PageQuery {

    public static final int DEFAULT_PAGE_NUM = 1;

    public static final int DEFAULT_PAGE_SIZE = 10;

    public static final int MAX_PAGE_SIZE = 100;

    private int pageNum = DEFAULT_PAGE_NUM;

    private int pageSize = DEFAULT_PAGE_SIZE;

    public int normalizedPageNum() {
        return Math.max(pageNum, DEFAULT_PAGE_NUM);
    }

    public int normalizedPageSize() {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public long offset() {
        return (long) (normalizedPageNum() - 1) * normalizedPageSize();
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
