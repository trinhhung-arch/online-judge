// Tải chuẩn để đo host_factor (nfrplan 9.1, Bước 2.9).
//
// Ba tính chất bắt buộc, và mỗi cái loại bỏ một cách đo sai:
//
//  1. TẤT ĐỊNH — không random, không đọc giờ, không đọc input. Hai lần đo trên cùng một máy
//     phải ra cùng một con số, nếu không thì "drift 8%" là nhiễu chứ không phải tín hiệu.
//  2. KHÔNG dùng bộ nhớ đáng kể — nếu tải chuẩn ăn RAM thì con số đo được phản ánh băng
//     thông bộ nhớ của máy chứ không phản ánh tốc độ chạy một bài thi đấu.
//  3. Trộn SỐ NGUYÊN và TRUY CẬP MẢNG, đúng hình dạng của bài thi đấu thật (vòng lặp chặt,
//     chỉ số mảng, số học 64-bit) — chứ không phải một vòng lặp trống mà trình biên dịch
//     có thể tối ưu đi mất.
//  4. Chạy đủ LÂU (~0,5 giây) — một tải chuẩn 28ms thì nhiễu lập lịch của hệ điều hành lớn
//     hơn cả tín hiệu, và "drift 8%" sẽ báo động liên tục mà không có gì xảy ra cả.
#include <cstdint>
#include <cstdio>

int main() {
    static uint64_t table[4096];
    for (int i = 0; i < 4096; ++i) {
        table[i] = static_cast<uint64_t>(i) * 2654435761u;
    }
    uint64_t acc = 1;
    for (int round = 0; round < 50000; ++round) {
        for (int i = 0; i < 4096; ++i) {
            acc = acc * 6364136223846793005ULL + table[(acc >> 33) & 4095] + i;
            table[i] ^= acc;
        }
    }
    // In ra để trình biên dịch không xoá cả vòng lặp — nhưng giá trị thì không ai đọc.
    printf("%llu\n", static_cast<unsigned long long>(acc));
    return 0;
}
