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
