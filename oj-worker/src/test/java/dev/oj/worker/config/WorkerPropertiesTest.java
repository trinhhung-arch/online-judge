package dev.oj.worker.config;

import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.config.WorkerProperties.Sandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesTest {

    @Test
    @DisplayName("★ SB-18 · cache đặt dưới thư mục isolate gắn vào box, dưới PCH hay dưới gốc box → từ chối khởi động")
    void cache_trong_tam_nhin_cua_box_thi_tu_choi() {
        for (String dir : new String[]{"/usr/local/oj-cache", "/lib/oj", "/bin/x", "/dev/shm/oj",
                "/opt/oj/pch/cache", "/var/local/lib/isolate/cache"}) {
            assertThatThrownBy(() -> sandbox(true, dir))
                    .as(dir)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cache.dir");
        }
    }

    @Test
    @DisplayName("SB-18 · cache ở chỗ an toàn, tên chỉ trùng tiền tố, hoặc sandbox tắt → khởi động bình thường")
    void cache_ngoai_box_thi_cho_qua() {
        for (String dir : new String[]{"/var/tmp/oj-worker", "/usrlocal/oj", "/srv/oj-cache"}) {
            assertThatCode(() -> sandbox(true, dir)).as(dir).doesNotThrowAnyException();
        }
        assertThatCode(() -> sandbox(false, "/usr/local/oj-cache"))
                .as("không có isolate thì không có box nào để lộ")
                .doesNotThrowAnyException();
    }

    private static Sandbox sandbox(boolean enabled, String cacheDir) {
        Sandbox s = WorkerFixtures.sandbox(Path.of("/var/tmp/oj-worker"));
        return new Sandbox(enabled, s.isolateBinary(), s.boxRoot(), s.firstBoxId(),
                s.watchdogSlack(), s.programPath(), s.compile(), s.run(),
                new Sandbox.Cache(Path.of(cacheDir), s.cache().maxEntries()), s.benchmark());
    }
}
