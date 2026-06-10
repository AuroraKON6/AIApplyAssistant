package com.getjobs.application.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SkyvernProcessManager {

    private Process skyvernProcess;
    private long processStartTime;
    private long lastEnvModified;

    public boolean isRunning() {
        return skyvernProcess != null && skyvernProcess.isAlive();
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", isRunning());
        if (isRunning()) {
            status.put("pid", skyvernProcess.pid());
            status.put("uptimeMs", System.currentTimeMillis() - processStartTime);
        }
        status.put("envPath", envPath().toString());
        status.put("envExists", Files.exists(envPath()));
        return status;
    }

    public void startIfEnvExists() {
        if (Files.exists(envPath())) {
            start();
        }
    }

    public void restart() {
        stop();
        start();
    }

    public void restartIfNeeded() {
        long currentMod = getLastModified();
        if (isRunning() && currentMod == lastEnvModified) {
            log.info("Skyvern is running and .env unchanged, skipping restart");
            return;
        }
        restart();
    }

    private void start() {
        if (isRunning()) {
            log.info("Skyvern is already running (pid={})", skyvernProcess.pid());
            return;
        }

        Path skyvernDir = skyvernDir();
        if (!Files.exists(skyvernDir.resolve("skyvern").resolve("config.py"))) {
            log.warn("Skyvern source not found at {}, skipping start", skyvernDir);
            return;
        }

        Map<String, String> env = loadEnv();
        if (env.isEmpty()) {
            log.warn("No .env found or empty, skipping Skyvern start");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "py", "-3.11", "-X", "utf8", "-m", "uvicorn",
                    "skyvern.forge.api_app:create_api_app",
                    "--host", "127.0.0.1", "--port", "8001", "--factory"
            );
            pb.directory(skyvernDir.toFile());
            pb.redirectErrorStream(false);

            Map<String, String> pbEnv = pb.environment();
            pbEnv.putAll(System.getenv());
            pbEnv.putAll(env);

            pb.redirectOutput(ProcessBuilder.Redirect.to(skyvernDir.resolve("skyvern-8001.log").toFile()));
            pb.redirectError(ProcessBuilder.Redirect.to(skyvernDir.resolve("skyvern-8001.err.log").toFile()));

            skyvernProcess = pb.start();
            processStartTime = System.currentTimeMillis();
            lastEnvModified = getLastModified();
            log.info("Skyvern started (pid={})", skyvernProcess.pid());
        } catch (IOException e) {
            log.error("Failed to start Skyvern: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start Skyvern: " + e.getMessage(), e);
        }
    }

    private void stop() {
        if (!isRunning()) {
            return;
        }
        long pid = skyvernProcess.pid();
        log.info("Stopping Skyvern (pid={})...", pid);
        skyvernProcess.destroy();
        try {
            if (!skyvernProcess.waitFor(10, TimeUnit.SECONDS)) {
                skyvernProcess.destroyForcibly();
                skyvernProcess.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            skyvernProcess.destroyForcibly();
        }
        log.info("Skyvern stopped (pid={})", pid);
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private Map<String, String> loadEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        Path path = envPath();
        if (!Files.exists(path)) return env;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                }
                env.put(key, value);
            }
        } catch (IOException e) {
            log.warn("Failed to read .env: {}", e.getMessage());
        }
        return env;
    }

    private long getLastModified() {
        try {
            return Files.exists(envPath()) ? Files.getLastModifiedTime(envPath()).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private Path skyvernDir() {
        return Path.of("runtime", "skyvern").toAbsolutePath().normalize();
    }

    private Path envPath() {
        return skyvernDir().resolve(".env");
    }
}
