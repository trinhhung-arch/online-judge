package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private static final String BAM_GIA =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7lNlS3ITwYqLzKz9M4pOSf5lXO4/Bxu";

    private final BCryptPasswordEncoder encoder;
    private final Semaphore suat;
    private final Duration cho;

    public BCryptPasswordHasher(AppProperties properties) {
        this.encoder = new BCryptPasswordEncoder(properties.auth().bcryptCost());
        this.suat = new Semaphore(properties.auth().bcryptConcurrency(), true);
        this.cho = properties.auth().bcryptWait();
    }

    @Override
    public String bam(String matKhauTho) {
        return trongTran(() -> encoder.encode(matKhauTho));
    }

    @Override
    public boolean khop(String matKhauTho, String bamDaLuu) {
        return trongTran(() -> {
            if (bamDaLuu == null) {
                encoder.matches(matKhauTho, BAM_GIA);
                return false;
            }
            return encoder.matches(matKhauTho, bamDaLuu);
        });
    }

    private <T> T trongTran(java.util.function.Supplier<T> viec) {
        boolean coSuat;
        try {
            coSuat = suat.tryAcquire(cho.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw IdentityException.heThongBan(cho);
        }
        if (!coSuat) {
            throw IdentityException.heThongBan(Duration.ofSeconds(5));
        }
        try {
            return viec.get();
        } finally {
            suat.release();
        }
    }
}
