package dev.oj.worker.sandbox;

/**
 * Sandbox không làm được việc của nó — <b>không phải</b> lỗi của bài nộp.
 *
 * <p>Mọi lần ném ra class này đều kết thúc bằng verdict {@code IE}, và API sẽ cho chấm lại
 * tối đa 2 lần (FR-SUB-12). Đây chính là ranh giới mà {@code oj-worker/CLAUDE.md} mục 6 vẽ:
 * "không bao giờ đoán một verdict" — {@code isolate} không khởi tạo được box thì đó là chuyện
 * của máy chấm, và nói với thí sinh rằng chương trình của họ sai là nói dối.
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
