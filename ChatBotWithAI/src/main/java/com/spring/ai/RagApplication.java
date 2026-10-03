package com.spring.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(RagApplication.class, args);
    }

    /**
     * Automatically loads environment variables from .env file into System properties
     * so neither hardcoding nor manual terminal export is needed.
     */
    private static void loadDotEnv() {
        List<Path> candidatePaths = List.of(
                Paths.get(".env"),
                Paths.get("ChatBotWithAI", ".env"),
                Paths.get("..", ".env")
        );

        for (Path path : candidatePaths) {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                try {
                    Files.lines(path)
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
                            .forEach(line -> {
                                int eqIdx = line.indexOf('=');
                                String key = line.substring(0, eqIdx).trim();
                                String val = line.substring(eqIdx + 1).trim();
                                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                                    val = val.substring(1, val.length() - 1);
                                }
                                if (!key.isEmpty() && System.getProperty(key) == null && System.getenv(key) == null) {
                                    System.setProperty(key, val);
                                }
                            });
                    System.out.println("[INFO] Successfully loaded environment from: " + path.toAbsolutePath());
                    break;
                } catch (IOException e) {
                    System.err.println("[WARN] Failed reading .env file: " + e.getMessage());
                }
            }
        }
    }
}
