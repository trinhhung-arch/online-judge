package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/**
 * {@link StandingsLock} bằng khoá tư vấn của Postgres, mức transaction.
 *
 * <h2>Vì sao khoá tư vấn chứ không phải {@code SELECT ... FROM contests FOR UPDATE}</h2>
 * Khoá dòng {@code contests} thì mọi thứ khác chạm dòng ấy cũng phải xếp hàng sau một lô bảng
 * xếp hạng: {@code FOR UPDATE} còn chặn cả kiểm tra khoá ngoại của {@code INSERT submissions}
 * — tức đường nộp bài, đúng lúc kỳ thi đông nhất. Khoá tư vấn chỉ chặn những ai xin đúng nó.
 *
 * <h2>Khoá gồm hai số</h2>
 * {@code pg_advisory_xact_lock(vùng, khoá)}: {@link #VUNG} riêng cho bảng xếp hạng, nên một
 * tính năng sau này dùng khoá tư vấn theo id của thứ khác không đụng nhầm vào đây. Dạng hai
 * số nằm ở không gian khác hẳn dạng một số {@code bigint} ({@code objsubid} = 2 trong
 * {@code pg_locks}), và đọc được bằng mắt: {@code classid} là vùng, {@code objid} là id kỳ thi.
 *
 * <p>{@code contests.id} là {@code BIGINT}, còn khoá chỉ nhận {@code integer}:
 * {@link Long#hashCode} giữ nguyên id dưới 2³¹ và gập phần trên vào. Hai kỳ thi trùng khoá thì
 * chỉ phải chờ nhau — chậm hơn, không sai — nên phép gập không cần chính xác tuyệt đối.
 */
@Repository
public class JdbcStandingsLock implements StandingsLock {

    /** {@code 0x53544E44} = ASCII {@code "STND"}: nhìn {@code pg_locks.classid} là biết của ai. */
    static final int VUNG = 0x53544E44;

    private static final String KHOA = """
            SELECT pg_advisory_xact_lock(:vung, :khoa)
            """;

    private final JdbcClient jdbc;
    private final DataSource appDataSource;

    public JdbcStandingsLock(@Qualifier("appJdbcClient") JdbcClient jdbc,
                             @Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = jdbc;
        this.appDataSource = appDataSource;
    }

    @Override
    public void khoaKyThi(long contestId) {
        // Transaction phải của ĐÚNG pool app. Một transaction của pool judge vẫn làm
        // isActualTransactionActive() trả true, nhưng câu dưới — qua appJdbc — lại chạy trên
        // một connection khác, tự commit, và khoá nhả ngay khi vừa giữ được.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(appDataSource)) {
            throw new IllegalStateException("khoaKyThi phải chạy trong một transaction của pool "
                    + "app — ngoài nó, khoá nhả ngay khi vừa giữ được (javadoc StandingsLock)");
        }
        jdbc.sql(KHOA)
                .param("vung", VUNG)
                .param("khoa", Long.hashCode(contestId))
                .query((rs, i) -> Boolean.TRUE)
                .single();
    }
}
