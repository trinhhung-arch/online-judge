package dev.oj.worker.sandbox;

import dev.oj.worker.sandbox.IsolateMeta.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mọi khối {@code meta} dưới đây là <b>bản chép nguyên văn</b> từ isolate 2.6 chạy thật, chứ
 * không phải chuỗi bịa ra. Một parser được kiểm bằng dữ liệu do chính người viết parser nghĩ
 * ra thì chỉ chứng minh rằng hai lần đoán giống nhau.
 */
class IsolateMetaTest {

    @Test
    @DisplayName("không có status = chạy xong bình thường")
    void chayXong() {
        IsolateMeta meta = IsolateMeta.parse(List.of(
                "time:0.004", "time-wall:0.031", "max-rss:1636", "cg-mem:256", "exitcode:0"));

        assertThat(meta.outcome()).isEqualTo(Outcome.OK);
        assertThat(meta.cpuTimeMs()).isEqualTo(4);
        assertThat(meta.wallTimeMs()).isEqualTo(31);
        assertThat(meta.memoryKb()).isEqualTo(256);
    }

    @Test
    @DisplayName("TO = vượt giới hạn thời gian")
    void vuotGio() {
        IsolateMeta meta = IsolateMeta.parse(List.of(
                "status:TO", "message:Time limit exceeded", "killed:1",
                "time:1.090", "time-wall:1.201", "cg-mem:512"));

        assertThat(meta.outcome()).isEqualTo(Outcome.TIME_LIMIT);
        assertThat(meta.killed()).isTrue();
        assertThat(meta.hitWallClock()).isFalse();
    }

    @Test
    @DisplayName("TO kèm 'wall clock' phân biệt được ngủ với tính toán")
    void vuotWall() {
        assertThat(IsolateMeta.parse(List.of(
                "status:TO", "message:Time limit exceeded (wall clock)", "killed:1",
                "time:0.003", "time-wall:2.008")).hitWallClock()).isTrue();
    }

    @Test
    @DisplayName("★ SG + cg-oom-killed là MLE, KHÔNG phải RE")
    void oomLaMle() {
        IsolateMeta meta = IsolateMeta.parse(List.of(
                "time:0.059", "cg-mem:65536", "cg-oom-killed:1", "exitsig:9",
                "status:SG", "message:Caught fatal signal 9"));

        assertThat(meta.outcome())
                .as("báo RE cho một chương trình chỉ thiếu bộ nhớ là đẩy thí sinh đi tìm một "
                        + "lỗi con trỏ không tồn tại")
                .isEqualTo(Outcome.MEMORY_LIMIT);
    }

    @Test
    @DisplayName("SG không kèm OOM là RE thật (SIGSEGV)")
    void tinHieuLaRe() {
        assertThat(IsolateMeta.parse(List.of(
                "exitsig:11", "status:SG", "message:Caught fatal signal 11")).outcome())
                .isEqualTo(Outcome.RUNTIME_ERROR);
    }

    @Test
    @DisplayName("RE = thoát với mã khác 0")
    void thoatMaKhacKhong() {
        assertThat(IsolateMeta.parse(List.of(
                "exitcode:3", "status:RE", "message:Exited with error status 3")).outcome())
                .isEqualTo(Outcome.RUNTIME_ERROR);
    }

    @Test
    @DisplayName("★ XX và mọi mã lạ đều là IE — không map bừa sang RE")
    void maLaLaIe() {
        assertThat(IsolateMeta.parse(List.of(
                "status:XX", "message:open(\"/dev/stdin\"): No such file")).outcome())
                .isEqualTo(Outcome.INTERNAL_ERROR);

        assertThat(IsolateMeta.parse(List.of("status:ZZ-mã-isolate-tương-lai")).outcome())
                .as("IE thì API cho chấm lại 2 lần; RE là verdict cuối và không sửa được")
                .isEqualTo(Outcome.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("mã lạ thì diagnostic giữ NGUYÊN VĂN file meta")
    void giuNguyenVanKhiLa() {
        assertThat(IsolateMeta.parse(List.of("status:XX", "message:chuyện lạ")).diagnostic())
                .contains("status:XX")
                .contains("message:chuyện lạ");
    }

    @Test
    @DisplayName("thiếu cg-mem thì lùi về max-rss")
    void luiVeMaxRss() {
        assertThat(IsolateMeta.parse(List.of("max-rss:2048")).memoryKb()).isEqualTo(2048);
    }
}
