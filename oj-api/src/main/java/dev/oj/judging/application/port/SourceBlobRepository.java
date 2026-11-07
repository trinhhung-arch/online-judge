package dev.oj.judging.application.port;

import dev.oj.judging.domain.SourceBlob;

public interface SourceBlobRepository {

    void saveIfAbsent(SourceBlob blob);
}
