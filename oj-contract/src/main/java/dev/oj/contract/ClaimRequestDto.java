package dev.oj.contract;

public record ClaimRequestDto(
        String hostName,
        String arch,
        int freeSlots) {

    public static final String ARCH_ARM64 = "arm64";
    public static final String ARCH_AMD64 = "amd64";

    public ClaimRequestDto {
        ContractChecks.requireText(hostName, "hostName");
        ContractChecks.requireText(arch, "arch");
        if (!ARCH_ARM64.equals(arch) && !ARCH_AMD64.equals(arch)) {
            throw new IllegalArgumentException(
                    "arch phải là '" + ARCH_ARM64 + "' hoặc '" + ARCH_AMD64 + "', nhận được: " + arch);
        }
        ContractChecks.requireAtLeast(freeSlots, 1, "freeSlots");
    }

    public static ClaimRequestDto single(String hostName, String arch) {
        return new ClaimRequestDto(hostName, arch, 1);
    }
}
