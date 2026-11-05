package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

@Repository
public class JdbcStandingsLock implements StandingsLock {

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
