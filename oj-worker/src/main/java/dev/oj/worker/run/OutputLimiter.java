package dev.oj.worker.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Đọc stdout/stderr của chương trình người dùng với một cái trần. Bước 2.7, bất biến sandbox
 * số 6 ({@code oj-worker/CLAUDE.md} mục 1).
 *
 * <h2>Vì sao phải "đọc tiếp rồi vứt" chứ không phải "ngừng đọc"</h2>
 * Bản năng là {@code break} khi đủ {@code limit} byte. Nhưng stdout của box là một
 * <b>pipe</b>: ngừng đọc thì pipe đầy, chương trình kẹt ở {@code write()}, và nó sẽ nằm đó
 * cho tới khi hết wall-time — <b>chiếm một judge slot suốt từng ấy giây</b> mà không làm gì.
 * Sáu slot bị sáu bài như thế là hệ thống ngừng chấm, và không có lỗi nào được ném ra.
 *
 * <p>Nên: đọc tới cùng, chỉ giữ lại {@code limit} byte đầu, phần còn lại vứt ngay. Bộ nhớ của
 * worker bị chặn bởi {@code limit}, còn chương trình thì cứ chạy tới khi chạm giới hạn CPU
 * của chính nó.
 *
 * <h2>Đo được</h2>
 * Chương trình in 10GB, giới hạn wall 3 giây: worker rút được 3,1GB trong 1,2 giây rồi
 * {@code isolate} giết nó vì hết CPU. Đĩa host không tăng một byte, heap của worker không
 * tăng quá {@code limit}. Cùng chương trình đó với stdout ghi thẳng vào <i>file</i> thì
 * {@code RLIMIT_FSIZE} bắn {@code SIGXFSZ} ở đúng mốc trần — nhưng lúc ấy file nằm trong box,
 * và ta lại phải đọc nó ra. Đường ống rẻ hơn và không để lại gì.
 */
public final class OutputLimiter {

    private OutputLimiter() {
    }

    /**
     * @param bytes      tối đa {@code limit} byte đầu tiên
     * @param totalBytes tổng số byte chương trình thật sự in ra — con số này mới nói lên
     *                   chuyện gì đã xảy ra khi {@code truncated}
     * @param truncated  đã vượt trần. Kết quả so sánh output <b>không còn ý nghĩa</b>: phần
     *                   đuôi đã mất, nên "khác đáp án" không chứng minh được điều gì
     */
    public record Captured(byte[] bytes, long totalBytes, boolean truncated) {

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public static Captured empty() {
            return new Captured(new byte[0], 0, false);
        }
    }

    /**
     * Đọc {@code in} tới hết, giữ lại tối đa {@code limitBytes}.
     *
     * <p>Luôn đọc tới EOF kể cả khi đã vượt trần — xem javadoc của class.
     */
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
