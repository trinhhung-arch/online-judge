package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.TestdataStore;
import dev.oj.problems.application.published.TestdataBytes;
import dev.oj.problems.domain.TestdataKeys;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

@Component
public class StoreTestdataBytes implements TestdataBytes {

    private final TestdataStore store;

    public StoreTestdataBytes(TestdataStore store) {
        this.store = store;
    }

    @Override
    public Optional<InputStream> doc(String sha256) {
        if (!TestdataKeys.hopLe(sha256)) {
            return Optional.empty();
        }
        if (!store.daCo(sha256)) {
            return Optional.empty();
        }
        return Optional.of(store.doc(sha256));
    }
}
