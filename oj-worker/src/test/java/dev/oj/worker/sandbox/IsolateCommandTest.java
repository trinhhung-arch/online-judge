package dev.oj.worker.sandbox;

import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.config.WorkerProperties.Sandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsolateCommandTest {

    private static final Sandbox CFG = WorkerFixtures.sandbox(Path.of("/tmp/oj-test"));
    private static final Path META = Path.of("/tmp/oj-test/box.meta");

    @Test
    @DisplayName("không bao giờ có --share-net · --special-files · --inherit-fds · --full-env")
    void khongCoCoCam() {
        List<String> run = IsolateCommand.run(CFG, 0, META, 1000, 2000, 65536, List.of("/box/p"));
        List<String> compile = IsolateCommand.compile(CFG, 0, META, 10000, 524288,
                List.of("/usr/bin/g++", "-o", "prog", "Main.cpp"));

        assertThat(run).doesNotContain("--share-net", "--special-files", "--inherit-fds",
                "--full-env", "-e");
        assertThat(compile).doesNotContain("--share-net", "--special-files", "--inherit-fds",
                "--full-env", "-e");
    }

    @Test
    @DisplayName("assertNoForbiddenFlags chặn cả dòng lệnh do người sau này thêm")
    void chanCoCamOMoiDongLenh() {
        assertThatThrownBy(() -> IsolateCommand.assertNoForbiddenFlags(
                List.of("isolate", "--cg", "--share-net", "--run")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--share-net");
    }

    @Test
    @DisplayName("luôn có --cg: giới hạn do kernel ép, không do worker đếm")
    void luonBatCgroup() {
        assertThat(IsolateCommand.run(CFG, 3, META, 1000, 2000, 65536, List.of("/box/p")))
                .contains("--cg")
                .containsSequence("-b", "3")
                .containsSequence("-M", META.toString())
                .contains("--cg-mem", "65536");
    }

    @Test
    @DisplayName("bước chạy gỡ /proc và /tmp — hai đường rò rỉ mặc định của isolate")
    void goProcVaTmpOBuocChay() {
        assertThat(IsolateCommand.run(CFG, 0, META, 1000, 2000, 65536, List.of("/box/p")))
                .contains("--dir=/proc=", "--dir=/tmp=");
    }

    @Test
    @DisplayName("giới hạn thời gian dùng dấu chấm kể cả khi máy đặt locale vi-VN")
    void thoiGianKhongPhuThuocLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("vi-VN"));
            assertThat(IsolateCommand.run(CFG, 0, META, 1500, 3000, 65536, List.of("/box/p")))
                    .contains("1.500", "3.000");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("bước biên dịch cho nhiều tiến trình hơn bước chạy — g++ fork ra cc1plus")
    void bienDichDuocForkNhieuHon() {
        assertThat(IsolateCommand.compile(CFG, 0, META, 10000, 524288, List.of("/usr/bin/g++")))
                .contains("--processes=" + CFG.compile().processes());
        assertThat(IsolateCommand.run(CFG, 0, META, 1000, 2000, 65536, List.of("/box/p")))
                .contains("--processes=" + CFG.run().processes());
    }

    @Test
    @DisplayName("★ PCH chỉ gắn vào box lúc biên dịch, không lúc chạy")
    void pch_chi_o_buoc_bien_dich() {
        String mount = "--dir=" + IsolateCommand.PCH_DIR + "=" + CFG.compile().pchDir() + ":maybe";

        assertThat(IsolateCommand.compile(CFG, 0, META, 10_000, 262_144, List.of("/usr/bin/g++")))
                .contains(mount);
        assertThat(IsolateCommand.run(CFG, 0, META, 1000, 2000, 262_144, List.of("/box/prog")))
                .as("bước chạy không có lý do nào để thấy PCH")
                .doesNotContain(mount);
    }

    @Test
    @DisplayName("host chưa dựng PCH thì dùng cờ :maybe, không phải một lỗi khởi động")
    void thieu_pch_thi_hong_nhe() {
        assertThat(IsolateCommand.compile(CFG, 0, META, 10_000, 262_144, List.of("/usr/bin/g++")))
                .anyMatch(arg -> arg.startsWith("--dir=" + IsolateCommand.PCH_DIR + "=")
                        && arg.endsWith(":maybe"));
    }
}
