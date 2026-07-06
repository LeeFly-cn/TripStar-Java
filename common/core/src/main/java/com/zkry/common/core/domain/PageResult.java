package com.zkry.common.core.domain;

import java.util.Collections;
import java.util.List;

public class PageResult<T> {

    private List<T> records;

    private long total;

    private int pageNum;

    private int pageSize;

    public PageResult() {
        this.records = Collections.emptyList();
    }

    private PageResult(List<T> records, long total, int pageNum, int pageSize) {
        this.records = records == null ? Collections.emptyList() : records;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> records, long total, PageQuery pageQuery) {
        return of(records, total, pageQuery.normalizedPageNum(), pageQuery.normalizedPageSize());
    }

    public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize) {
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    public static <T> PageResult<T> empty(PageQuery pageQuery) {
        return of(Collections.emptyList(), 0, pageQuery);
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
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
