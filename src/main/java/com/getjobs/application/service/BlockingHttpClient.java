package com.getjobs.application.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public final class BlockingHttpClient {
    private BlockingHttpClient() {}

    public record Response(int statusCode, String body) {}

    public static Response get(String url, Map<String, String> headers, int timeoutSeconds) {
        return request("GET", url, headers, null, timeoutSeconds);
    }

    public static Response postJson(String url, Map<String, String> headers, String body, int timeoutSeconds) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(headers == null ? Map.of() : headers);
        merged.put("Content-Type", "application/json");
        return request("POST", url, merged, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), timeoutSeconds);
    }

    public static Response multipartFile(String url, Map<String, String> headers, String fieldName,
                                         String fileName, String contentType, byte[] fileBytes,
                                         int timeoutSeconds) {
        String boundary = "----GetJobsBoundary" + UUID.randomUUID();
        Map<String, String> merged = new java.util.LinkedHashMap<>(headers == null ? Map.of() : headers);
        merged.put("Content-Type", "multipart/form-data; boundary=" + boundary);

        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + fileBytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(fileBytes, 0, body, prefix.length, fileBytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + fileBytes.length, suffix.length);
        return request("POST", url, merged, body, timeoutSeconds);
    }

    private static Response request(String method, String url, Map<String, String> headers, byte[] body, int timeoutSeconds) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Math.max(1, timeoutSeconds) * 1000);
            connection.setReadTimeout(Math.max(1, timeoutSeconds) * 1000);
            connection.setInstanceFollowRedirects(true);
            if (headers != null) {
                HttpURLConnection target = connection;
                headers.forEach((key, value) -> {
                    if (key != null && value != null) {
                        target.setRequestProperty(key, value);
                    }
                });
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return new Response(statusCode, responseBody);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
