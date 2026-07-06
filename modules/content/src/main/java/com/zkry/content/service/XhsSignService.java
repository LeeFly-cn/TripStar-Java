package com.zkry.content.service;

import com.zkry.common.json.utils.JsonUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class XhsSignService {

    private static final Logger log = LoggerFactory.getLogger(XhsSignService.class);
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String DEFAULT_CLASSPATH_SIGN_DIR = "xhs_sign";
    private static final String MAIN_SIGN_FILE = "xhs_xs_xsc_56.js";
    private static final List<String> SIGN_RESOURCE_FILES = List.of(
        MAIN_SIGN_FILE,
        "xhs_xray.js",
        "xhs_xray_pack1.js",
        "xhs_xray_pack2.js"
    );

    @Value("${tripstar.content.xhs.node-command:node}")
    private String nodeCommand;

    @Value("${tripstar.content.xhs.sign-dir:classpath:xhs_sign}")
    private String signDir;

    @Value("${tripstar.content.xhs.sign-timeout-seconds:15}")
    private long timeoutSeconds;

    private volatile Path extractedClasspathSignDir;

    public SignedRequest sign(String cookie, String api, Map<String, Object> data, String method) {
        Path resolvedSignDir = resolveSignDir(api);

        String payload = data == null ? "" : JsonUtils.toJsonString(data);
        String encodedCookie = base64(cookie == null ? "" : cookie);
        String encodedPayload = base64(payload == null ? "" : payload);
        long startedAt = System.currentTimeMillis();
        log.info("[XHS-SIGN] 开始生成签名 api={} method={} signDir={} payloadLength={} cookieConfigured={}",
            api,
            method == null || method.isBlank() ? "POST" : method,
            resolvedSignDir,
            payload == null ? 0 : payload.length(),
            cookie != null && !cookie.isBlank());

        Path scriptPath = null;
        try {
            scriptPath = Files.createTempFile("tripstar-xhs-sign-", ".cjs");
            Files.writeString(scriptPath, nodeScript(), StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(
                nodeCommand,
                scriptPath.toString(),
                resolvedSignDir.toString(),
                api,
                method == null || method.isBlank() ? "POST" : method,
                encodedCookie,
                encodedPayload
            );
            builder.directory(resolvedSignDir.toFile());
            builder.redirectErrorStream(true);

            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                log.warn("[XHS-SIGN] 签名执行超时 api={} timeoutSeconds={}", api, timeoutSeconds);
                throw new XhsCookieExpiredException("小红书签名执行超时");
            }
            if (process.exitValue() != 0) {
                String safeOutput = safeNodeOutput(output);
                log.warn("[XHS-SIGN] 签名执行失败 api={} exitCode={} outputLength={} output={}",
                    api, process.exitValue(), output == null ? 0 : output.length(), safeOutput);
                throw new XhsCookieExpiredException("小红书签名执行失败：" + safeOutput);
            }
            SignedRequest signedRequest = parseSignedRequest(output, cookie);
            log.info("[XHS-SIGN] 签名生成成功 api={} headerCount={} elapsedMs={}",
                api, signedRequest.headers().size(), System.currentTimeMillis() - startedAt);
            return signedRequest;
        } catch (IOException ex) {
            log.warn("[XHS-SIGN] 无法启动 Node.js 签名进程 api={} nodeCommand={} reason={}",
                api, nodeCommand, ex.getMessage());
            throw new XhsCookieExpiredException("无法启动 Node.js 小红书签名进程，请确认 node 可用", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[XHS-SIGN] 签名执行被中断 api={}", api);
            throw new XhsCookieExpiredException("小红书签名执行被中断", ex);
        } finally {
            if (scriptPath != null) {
                try {
                    Files.deleteIfExists(scriptPath);
                } catch (IOException ex) {
                    log.debug("[XHS-SIGN] 临时脚本删除失败 path={} reason={}", scriptPath, ex.getMessage());
                }
            }
        }
    }

    private SignedRequest parseSignedRequest(String output, String cookie) {
        JsonNode root = JsonUtils.parseTree(output);
        if (root == null || !root.isObject()) {
            log.warn("[XHS-SIGN] 签名输出不是 JSON outputLength={} output={}",
                output == null ? 0 : output.length(), safeNodeOutput(output));
            throw new XhsCookieExpiredException("小红书签名输出不是 JSON");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headerNode = root.path("headers");
        if (headerNode.isObject()) {
            headerNode.properties().forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText("")));
        }
        headers.put("Cookie", cookie == null ? "" : cookie);
        return new SignedRequest(headers, root.path("body").asText(""));
    }

    /**
     * 通过 Node.js 执行项目内置的小红书签名资产。
     *
     * <p>这里刻意只打印路径、耗时和长度，不打印 Cookie、x-s 等敏感签名值。
     */
    private String nodeScript() {
        return """
            const path = require('path');
            const crypto = require('crypto');
            const args = process.argv.slice(2);
            const signDir = args[0];
            const api = args[1];
            const method = args[2] || 'POST';
            function decodeBase64(text) {
              return Buffer.from(text || '', 'base64').toString('utf8');
            }
            const cookie = decodeBase64(args[3] || '');
            const bodyText = decodeBase64(args[4] || '');
            const quiet = { log(){}, warn(){}, error(){}, info(){}, debug(){} };
            global.console = quiet;
            process.chdir(signDir);
            const xs = require(path.join(signDir, 'xhs_xs_xsc_56.js'));
            require(path.join(signDir, 'xhs_xray.js'));
            function cookieMap(text) {
              const map = {};
              const sep = text.includes('; ') ? '; ' : ';';
              text.split(sep).forEach(item => {
                const index = item.indexOf('=');
                if (index > 0) map[item.slice(0, index).trim()] = item.slice(index + 1).trim();
              });
              return map;
            }
            function traceId(length = 16) {
              const chars = 'abcdef0123456789';
              let value = '';
              for (let i = 0; i < length; i++) value += chars[Math.floor(16 * Math.random())];
              return value;
            }
            const cookies = cookieMap(cookie);
            const a1 = cookies.a1 || '';
            const data = bodyText ? JSON.parse(bodyText) : '';
            const ret = xs.get_request_headers_params(api, data, a1, method);
            const headers = {
              'authority': 'edith.xiaohongshu.com',
              'accept': 'application/json, text/plain, */*',
              'accept-language': 'zh-CN,zh;q=0.9,en;q=0.8',
              'cache-control': 'no-cache',
              'content-type': 'application/json;charset=UTF-8',
              'origin': 'https://www.xiaohongshu.com',
              'pragma': 'no-cache',
              'referer': 'https://www.xiaohongshu.com/',
              'sec-ch-ua': '"Not A(Brand";v="99", "Microsoft Edge";v="121", "Chromium";v="121"',
              'sec-ch-ua-mobile': '?0',
              'sec-ch-ua-platform': '"Windows"',
              'sec-fetch-dest': 'empty',
              'sec-fetch-mode': 'cors',
              'sec-fetch-site': 'same-site',
              'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0',
              'x-b3-traceid': traceId(21),
              'x-mns': 'unload',
              'x-s': ret.xs,
              'x-s-common': ret.xs_common,
              'x-t': String(ret.xt),
              'x-xray-traceid': typeof global.traceId === 'function' ? global.traceId() : crypto.randomBytes(16).toString('hex')
            };
            process.stdout.write(JSON.stringify({ headers, body: bodyText }));
            """;
    }

    private Path resolveSignDir(String api) {
        String configured = signDir == null ? "" : signDir.trim();
        if (configured.isBlank() || configured.startsWith(CLASSPATH_PREFIX)) {
            String resourceDir = configured.isBlank()
                ? DEFAULT_CLASSPATH_SIGN_DIR
                : configured.substring(CLASSPATH_PREFIX.length());
            return extractClasspathSignDir(normalizeResourceDir(resourceDir), api);
        }

        Path resolved = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.exists(resolved.resolve(MAIN_SIGN_FILE))) {
            log.warn("[XHS-SIGN] 签名文件不存在 signDir={} api={}", resolved, api);
            throw new XhsCookieExpiredException("小红书签名文件不存在: " + resolved);
        }
        return resolved;
    }

    private String normalizeResourceDir(String resourceDir) {
        String normalized = resourceDir == null || resourceDir.isBlank()
            ? DEFAULT_CLASSPATH_SIGN_DIR
            : resourceDir.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    /**
     * Classpath 资源在 jar 内不是普通目录，Node.js 的 require 无法直接读取。
     *
     * <p>第一次签名时把必要 JS 文件抽取到临时目录，后续请求复用同一目录。
     */
    private Path extractClasspathSignDir(String resourceDir, String api) {
        Path cached = extractedClasspathSignDir;
        if (cached != null && Files.exists(cached.resolve(MAIN_SIGN_FILE))) {
            return cached;
        }

        synchronized (this) {
            cached = extractedClasspathSignDir;
            if (cached != null && Files.exists(cached.resolve(MAIN_SIGN_FILE))) {
                return cached;
            }
            try {
                Path targetDir = Files.createTempDirectory("tripstar-xhs-sign-assets-").toAbsolutePath().normalize();
                targetDir.toFile().deleteOnExit();
                for (String fileName : SIGN_RESOURCE_FILES) {
                    copySignResource(resourceDir, fileName, targetDir);
                }
                extractedClasspathSignDir = targetDir;
                log.info("[XHS-SIGN] 已抽取内置签名资源 resourceDir={} targetDir={} fileCount={} api={}",
                    resourceDir, targetDir, SIGN_RESOURCE_FILES.size(), api);
                return targetDir;
            } catch (IOException ex) {
                log.warn("[XHS-SIGN] 抽取内置签名资源失败 resourceDir={} api={} reason={}",
                    resourceDir, api, ex.getMessage());
                throw new XhsCookieExpiredException("小红书内置签名资源抽取失败，请检查 xhs_sign 资源文件", ex);
            }
        }
    }

    private void copySignResource(String resourceDir, String fileName, Path targetDir) throws IOException {
        String resourcePath = resourceDir + "/" + fileName;
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IOException("classpath resource not found: " + resourcePath);
        }
        Path targetFile = targetDir.resolve(fileName);
        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
        targetFile.toFile().deleteOnExit();
    }

    public record SignedRequest(
        Map<String, String> headers,
        String body
    ) {
    }

    private String safeNodeOutput(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String sanitized = value
            .replaceAll("(?i)(a1=)[^;\\s]+", "$1***")
            .replaceAll("(?i)(web_session=)[^;\\s]+", "$1***")
            .replaceAll("(?i)(id_token=)[^;\\s]+", "$1***")
            .replaceAll("(?i)(cookie\\s*[:=]\\s*)[^,}\\s]+", "$1***")
            .replaceAll("(?i)(x-s-common\\s*[:=]\\s*)[^,}\\s]+", "$1***")
            .replaceAll("(?i)(x-s\\s*[:=]\\s*)[^,}\\s]+", "$1***")
            .replaceAll("[\\r\\n]+", " | ");
        return truncate(sanitized, 500);
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

}
