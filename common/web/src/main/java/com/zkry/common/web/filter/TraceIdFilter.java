package com.zkry.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        long startTime = System.currentTimeMillis();
        Throwable error = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            error = ex;
            throw ex;
        } finally {
            logRequest(request, response, startTime, error);
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private void logRequest(
        HttpServletRequest request,
        HttpServletResponse response,
        long startTime,
        Throwable error
    ) {
        long elapsed = System.currentTimeMillis() - startTime;
        int status = response.getStatus();
        if (error != null && status < 400) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }

        String query = request.getQueryString();
        String uri = request.getRequestURI() + (StringUtils.hasText(query) ? "?" + query : "");
        String userAgent = request.getHeader("User-Agent");
        String remoteIp = remoteIp(request);

        if (error == null) {
            log.info("HTTP {} {} -> {} {}ms ip={} ua={}",
                request.getMethod(), uri, status, elapsed, remoteIp, safe(userAgent));
        } else {
            log.warn("HTTP {} {} -> {} {}ms ip={} error={}: {}",
                request.getMethod(), uri, status, elapsed, remoteIp,
                error.getClass().getSimpleName(), error.getMessage());
        }
    }

    private String remoteIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
