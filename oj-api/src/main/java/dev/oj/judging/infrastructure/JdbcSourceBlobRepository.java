package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SourceBlobRepository;
import dev.oj.judging.domain.SourceBlob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSourceBlobRepository implements SourceBlobRepository {

    private static final String SAVE_IF_ABSENT = """
            INSERT INTO source_blobs (sha256, content, byte_size)
            VALUES (:sha256, :content, :byteSize)
            ON CONFLICT (sha256) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public JdbcSourceBlobRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveIfAbsent(SourceBlob blob) {
        jdbc.sql(SAVE_IF_ABSENT)
                .param("sha256", blob.sha256())
                .param("content", blob.content())
                .param("byteSize", blob.byteSize())
                .update();
    }
}
