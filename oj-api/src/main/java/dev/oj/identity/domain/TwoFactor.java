package dev.oj.identity.domain;

public record TwoFactor(long userId, String secretEnc, boolean enabled, Long lastStep) {

    public boolean buocDaDung(long buoc) {
        return lastStep != null && buoc <= lastStep;
    }

    @Override
    public String toString() {
        return "TwoFactor[userId=" + userId + ", enabled=" + enabled + "]";
    }
}
