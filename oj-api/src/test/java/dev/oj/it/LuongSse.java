package dev.oj.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

final class LuongSse implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Thread reader;
    private volatile boolean closed;

    LuongSse(URI uri, String bearer) throws IOException, InterruptedException {
        HttpResponse<java.util.stream.Stream<String>> response = client.send(
                HttpRequest.newBuilder(uri).header("Authorization", bearer).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control"))
                .as("luồng SSE commit response sớm — KhongLuuDemApiFilter phải đặt header trước chain")
                .hasValue("no-store");

        reader = Thread.ofPlatform().daemon().start(() -> {
            try (var body = response.body()) {
                body.forEach(lines::add);
            } finally {
                closed = true;
            }
        });
    }

    String awaitLineContaining(String needle) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (String line : lines) {
                if (line.contains(needle)) {
                    return line;
                }
            }
            sleep();
        }
        throw new AssertionError(
                "Không thấy sự kiện chứa '" + needle + "' sau " + WAIT + ".\nĐã nhận:\n"
                        + String.join("\n", lines));
    }

    void awaitClosed() {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && !closed) {
            sleep();
        }
        assertThat(closed)
                .as("bài đã DONE mà luồng vẫn mở là giữ một kết nối 5 phút cho một sự kiện "
                        + "sẽ không bao giờ tới")
                .isTrue();
    }

    String awaitSoDong(String needle, int soLan) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<String> khop = lines.stream().filter(l -> l.contains(needle)).toList();
            if (khop.size() >= soLan) {
                return khop.get(soLan - 1);
            }
            sleep();
        }
        throw new AssertionError("Không thấy lần thứ " + soLan + " của '" + needle + "' sau "
                + WAIT + ".\nĐã nhận:\n" + String.join("\n", lines));
    }

    String everything() {
        return String.join("\n", lines);
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() {
        reader.interrupt();
        client.close();
    }
}
