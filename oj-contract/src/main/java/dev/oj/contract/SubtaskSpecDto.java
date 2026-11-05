package dev.oj.contract;

import java.util.List;

public record SubtaskSpecDto(
        int ordinal,
        int points,
        SubtaskScoring scoring,
        List<Integer> dependsOn) {

    public static final int MAX_ORDINAL = 100;

    public SubtaskSpecDto {
        ContractChecks.requireRange(ordinal, 1, MAX_ORDINAL, "ordinal");
        ContractChecks.requireAtLeast(points, 0, "points");
        if (scoring == null) {
            throw new NullPointerException("scoring");
        }
        dependsOn = ContractChecks.frozen(dependsOn);
        for (Integer dependency : dependsOn) {
            if (dependency == null) {
                throw new NullPointerException("dependsOn chứa null");
            }
            if (dependency >= ordinal) {
                throw new IllegalArgumentException(
                        "Nhóm " + ordinal + " phụ thuộc nhóm " + dependency
                                + " — chỉ được phụ thuộc nhóm có số thứ tự NHỎ HƠN, để đồ thị "
                                + "phụ thuộc không thể có chu trình");
            }
            ContractChecks.requireRange(dependency, 1, MAX_ORDINAL, "dependsOn");
        }
    }

    public boolean hasDependencies() {
        return !dependsOn.isEmpty();
    }

    public static SubtaskSpecDto of(int ordinal, int points) {
        return new SubtaskSpecDto(ordinal, points, SubtaskScoring.MIN, List.of());
    }
}
