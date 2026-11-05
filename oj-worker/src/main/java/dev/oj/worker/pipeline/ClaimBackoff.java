package dev.oj.worker.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClaimBackoff {

    private static final Logger log = LoggerFactory.getLogger(ClaimBackoff.class);

    private static final int BOI_TRAN = 60;

    private static final int MU_TOI_DA = 20;

    private final Duration nhipCoSo;
    private final Duration tran;

    private final AtomicBoolean dangMat = new AtomicBoolean();

    public ClaimBackoff(Duration nhipCoSo) {
        if (nhipCoSo == null || nhipCoSo.isZero() || nhipCoSo.isNegative()) {
            throw new IllegalArgumentException("nhịp cơ sở phải dương: " + nhipCoSo);
        }
        this.nhipCoSo = nhipCoSo;
        this.tran = nhipCoSo.multipliedBy(BOI_TRAN);
    }

    public Duration cho(int lienTiep) {
        if (lienTiep < 1) {
            throw new IllegalArgumentException("lienTiep phải ≥ 1, nhận: " + lienTiep);
        }
        Duration cho = nhipCoSo.multipliedBy(1L << Math.min(lienTiep - 1, MU_TOI_DA));
        return cho.compareTo(tran) > 0 ? tran : cho;
    }

    public boolean ghiNhanMat(String lyDo, Duration cho) {
        if (!dangMat.compareAndSet(false, true)) {
            return false;
        }
        log.warn("Mất kết nối tới API ({}). Các slot giãn nhịp xin việc — lần này chờ {}, "
                        + "tối đa {}. KHÔNG ghi thêm dòng nào cho tới khi API trả lời lại.",
                lyDo, cho, tran);
        return true;
    }

    public boolean ghiNhanCoLai() {
        if (!dangMat.get() || !dangMat.compareAndSet(true, false)) {
            return false;
        }
        log.info("API trả lời lại — các slot quay về nhịp bình thường {}.", nhipCoSo);
        return true;
    }
}
