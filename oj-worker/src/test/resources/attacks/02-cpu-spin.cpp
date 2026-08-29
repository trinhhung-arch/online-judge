// Tấn công 2/14 — vắt kiệt CPU.
// Kỳ vọng: status=TO do CPU time, killed:1. Đây là ca duy nhất trong 14 ca mà
// "bị giết" là hành vi ĐÚNG của một bài nộp hợp lệ, nên nó cũng là ca kiểm tra
// rằng đồng hồ đo là CPU time chứ không phải wall time.
int main() { volatile long long x = 0; for (;;) ++x; }
