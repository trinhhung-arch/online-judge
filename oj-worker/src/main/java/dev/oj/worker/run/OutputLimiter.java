package dev.oj.worker.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class OutputLimiter {

    private OutputLimiter() {
    }

    public record Captured(byte[] bytes, long totalBytes, boolean truncated) {

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public static Captured empty() {
            return new Captured(new byte[0], 0, false);
        }
    }

    public static Captured drain(InputStream in, long limitBytes) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            long room = limitBytes - kept.size();
            if (room > 0) {
                kept.write(buffer, 0, (int) Math.min(read, room));
            }
        }
        return new Captured(kept.toByteArray(), total, total > limitBytes);
    }
}
