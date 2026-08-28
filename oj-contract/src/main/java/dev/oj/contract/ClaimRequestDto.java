package dev.oj.contract;

/**
 * Worker xin việc: {@code POST /internal/judge/claim}.
 *
 * <p>Trả về {@code 200} kèm {@link JudgeJobDto}, hoặc <b>{@code 204 No Content}</b> khi hàng
 * đợi rỗng. Cố ý không có một {@code ClaimResponseDto} bọc ngoài với cờ {@code hasJob} —
 * 204 đã nói đúng điều đó, và một wrapper chỉ tạo thêm một trạng thái sai được
 * ({@code hasJob=true} mà {@code job=null}).
 *
 * <h2>Vì sao worker PULL chứ không phải server PUSH</h2>
 * Server không giữ danh sách worker, không heartbeat, không service discovery. Bật thêm một
 * worker là nó tự vào việc, <b>không sửa một dòng config nào phía API</b> — đó chính là chỉ
 * số S2 trong {@code nfrplan.md}. Đây cũng là thứ làm cho bài test scalability tuần 12
 * (chạy worker trên Mac + WSL của A + WSL của B, đo throughput cộng dồn) trở thành một việc
 * không tốn đồng nào.
 *
 * @param hostName  tên máy chấm, khớp {@code judge_hosts.name}. API tra ra id và cập nhật
 *                  {@code last_seen_at}; worker không biết id trong DB (bất biến #3)
 * @param arch      {@code arm64} hoặc {@code amd64}. Có mặt ở đây vì host là Mac ARM còn hai
 *                  máy dev là WSL x86, và cùng một bài chạy lệch 20-50% giữa hai kiến trúc.
 *                  Một con số thời gian không kèm kiến trúc là một con số vô nghĩa
 *                  ({@code nfrplan.md} 9.1)
 * @param freeSlots số box đang rảnh. M1 luôn xin 1 việc mỗi lần; trường này để sau này
 *                  {@code claim} trả về nhiều job một lượt mà không phải mở lại contract
 */
public record ClaimRequestDto(
        String hostName,
        String arch,
        int freeSlots) {

    public static final String ARCH_ARM64 = "arm64";
    public static final String ARCH_AMD64 = "amd64";

    public ClaimRequestDto {
        ContractChecks.requireText(hostName, "hostName");
        ContractChecks.requireText(arch, "arch");
        // Gương của CHECK (arch IN ('arm64','amd64')) trên judge_hosts.
        if (!ARCH_ARM64.equals(arch) && !ARCH_AMD64.equals(arch)) {
            throw new IllegalArgumentException(
                    "arch phải là '" + ARCH_ARM64 + "' hoặc '" + ARCH_AMD64 + "', nhận được: " + arch);
        }
        ContractChecks.requireAtLeast(freeSlots, 1, "freeSlots");
    }

    /** Xin đúng một việc — hành vi của M1. */
    public static ClaimRequestDto single(String hostName, String arch) {
        return new ClaimRequestDto(hostName, arch, 1);
    }
}
