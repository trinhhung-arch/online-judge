// Tấn công 14/14 — compiler bomb (template explosion).
//
// Đây là ca duy nhất tấn công BƯỚC BIÊN DỊCH, và là lý do bất biến #4 viết rõ
// "kể cả bước biên dịch". Không sandbox lúc compile thì g++ ăn hết RAM host trước
// khi có bất kỳ dòng mã người dùng nào được chạy.
//
// Mỗi mức nhân đôi số kiểu phải khởi tạo, nên bộ nhớ của g++ tăng theo 2^N: cỡ 26
// là đủ để vượt giới hạn RAM biên dịch trước khi vượt giới hạn thời gian.
template <int N, class A, class B>
struct Bomb {
    typedef typename Bomb<N - 1, A, Bomb<N - 1, A, B> >::type left;
    typedef typename Bomb<N - 1, B, Bomb<N - 1, B, A> >::type right;
    struct type { left l; right r; };
};
template <class A, class B>
struct Bomb<0, A, B> { struct type { A a; B b; }; };

Bomb<26, char, short>::type boom;
int main() { return 0; }
