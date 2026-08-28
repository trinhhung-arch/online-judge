package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;

/**
 * ★ <b>Seam quan trọng nhất của dự án.</b> Một job vào, một kết quả ra.
 *
 * <pre>
 *   M1   ScriptedJudgeRunner   KHÔNG thực thi một dòng mã người dùng nào
 *   M2   IsolateJudgeRunner    thật — isolate + cgroup v2
 * </pre>
 *
 * <h2>Vì sao interface này ra đời từ M1, khi chưa có gì để cắm vào</h2>
 * M1 (tuần 1-2) phải cho ra một vòng nộp-bài → verdict chạy được, nhưng {@code isolate} tới
 * tận M2 (tuần 3-4) và bản ARM còn phải build trong VM Linux trên Mac. Bản năng lúc đó là
 * viết một {@code new ProcessBuilder("g++", ...)} "chỉ để thử vòng lặp".
 *
 * <p><b>Đó là vi phạm bất biến #4</b>, và trong thực tế nó không bao giờ bị xoá — nó chỉ bị
 * quên ({@code build-order.md} Phần 0, điểm G). Với seam này thì bản tạm có chỗ đứng hợp
 * pháp, và ngày thay thế là một dòng cấu hình.
 *
 * <p>⛔ <b>Cổng chuyển:</b> {@code IsolateJudgeRunner} chỉ được đăng ký thay
 * {@code ScriptedJudgeRunner} khi <b>14/14 test tấn công xanh trong CI</b> — không sớm hơn
 * một giờ. Từ đó, mọi PR chạm sandbox chạy lại toàn bộ 14 test, kể cả PR "chỉ là refactor".
 */
public interface JudgeRunner {

    /**
     * Chấm một bài. <b>Không bao giờ ném ra ngoài</b>: mọi sự cố phải thành một
     * {@link JudgeResultDto} mang verdict {@code IE}.
     *
     * <p>Lý do là {@code ResultBuffer} — một ngoại lệ thoát ra khỏi đây sẽ làm slot đó mất một
     * lượt chấm mà API không được báo gì, và bài phải chờ hết lease (120s) mới được reaper
     * nhặt lại. Trả {@code IE} thì API cho chấm lại ngay, tối đa 2 lần (FR-SUB-12).
     *
     * <p><b>Không bao giờ đoán một verdict.</b> Không chắc chắn kết quả là gì thì đó là
     * {@code IE} — đoán sai một verdict trong contest thì không ai phát hiện ra, và đó mới là
     * điều tệ ({@code oj-worker/CLAUDE.md} mục 6).
     */
    JudgeResultDto run(JudgeJobDto job);
}
