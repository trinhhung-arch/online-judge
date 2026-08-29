package dev.oj.worker.pipeline;

import dev.oj.worker.WorkerFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotPoolTest {

    private final SlotPool pool = new SlotPool(WorkerFixtures.properties(Path.of("/tmp/oj-test")));

    @Test
    @DisplayName("mỗi slot một box id riêng — hai luồng cùng box là hai bài phá nhau")
    void boxIdKhongTrung() throws InterruptedException {
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            taken.add(pool.acquire(100));
        }
        assertThat(taken).doesNotHaveDuplicates().hasSize(pool.size());
    }

    @Test
    @DisplayName("hết box thì ném lỗi có tên, không treo mãi và không âm thầm bỏ bài")
    void hetBoxThiBaoLoi() throws InterruptedException {
        for (int i = 0; i < pool.size(); i++) {
            pool.acquire(100);
        }
        assertThatThrownBy(() -> pool.acquire(50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quên release");
    }

    @Test
    @DisplayName("trả box rồi thì mượn lại được")
    void traRoiMuonLai() throws InterruptedException {
        int boxId = pool.acquire(100);
        pool.release(boxId);
        assertThat(pool.available()).isEqualTo(pool.size());
    }

    @Test
    @DisplayName("số box = slots trong config, KHÔNG theo số core (ADR 008)")
    void soBoxTheoConfig() {
        assertThat(pool.size())
                .as("máy build có %d core; số slot phải độc lập với con số đó",
                        Runtime.getRuntime().availableProcessors())
                .isEqualTo(6);
    }
}
