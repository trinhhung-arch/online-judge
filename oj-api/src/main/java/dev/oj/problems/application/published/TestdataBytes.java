package dev.oj.problems.application.published;

import java.io.InputStream;
import java.util.Optional;

public interface TestdataBytes {

    Optional<InputStream> doc(String sha256);
}
