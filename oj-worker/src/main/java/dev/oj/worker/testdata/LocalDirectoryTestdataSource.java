package dev.oj.worker.testdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ConditionalOnProperty(name = "oj.worker.testdata-source", havingValue = "local")
@Component
public class LocalDirectoryTestdataSource implements TestdataSource {

    private final Path root;

    public LocalDirectoryTestdataSource(
            @org.springframework.beans.factory.annotation.Value(
                    "${oj.worker.sandbox.cache.dir}/testdata-store") Path root) {
        this.root = root;
    }

    @Override
    public byte[] fetch(String sha256) {
        Path file = root.resolve(sha256.substring(0, 2)).resolve(sha256);
        if (!Files.isRegularFile(file)) {
            throw new TestdataUnavailableException(
                    "Không có testdata " + sha256 + " trong kho cục bộ " + root
                            + ". Kho thật (MinIO) là Bước 4.11 — tới lúc đó, đổ testdata vào "
                            + "thư mục này theo cấu trúc <2 ký tự đầu>/<sha256 đầy đủ>");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TestdataUnavailableException("Không đọc được testdata " + sha256, e);
        }
    }
}
