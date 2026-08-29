#!/usr/bin/env bash
#
# Dựng precompiled header cho `bits/stdc++.h`. Bước 3.5 của docs/build-order.md.
#
# Gần như mọi bài thi C++ mở đầu bằng `#include <bits/stdc++.h>` — một header kéo theo toàn
# bộ thư viện chuẩn. Phân tích lại nó cho MỖI bài nộp là trả đi trả lại cùng một hoá đơn.
#
# ⛔ PCH chỉ được dùng khi CỜ BIÊN DỊCH KHỚP CHÍNH XÁC. Lệch một cờ thì GCC bỏ qua .gch
#    trong IM LẶNG — không cảnh báo, không lỗi, chỉ là chậm như cũ. Vì thế script này nhận
#    cờ từ tham số chứ không tự chế, và cách gọi đúng là truyền đúng chuỗi cờ nằm trong
#    languages.compile_command.
#
# Dùng:  sudo ./scripts/build-pch.sh "-std=gnu++20 -O2"
set -euo pipefail

FLAGS="${1:--std=gnu++20 -O2}"
PCH_DIR="${PCH_DIR:-/opt/oj/pch}"

HEADER="$(g++ -std=gnu++20 -E -x c++ - -v </dev/null 2>&1 \
    | sed -n '/#include <...>/,/End of search/p' \
    | grep '^ /' | tr -d ' ' \
    | while read -r dir; do [ -f "$dir/bits/stdc++.h" ] && echo "$dir/bits/stdc++.h" && break; done)"

if [[ -z "${HEADER}" ]]; then
    echo "Không tìm thấy bits/stdc++.h — nó là header của GCC, không phải chuẩn C++." >&2
    echo "Cài g++ rồi chạy lại. (Clang không có header này.)" >&2
    exit 1
fi
echo "==> Header: ${HEADER}"
echo "==> Cờ:     ${FLAGS}"

# .gch phải nằm CẠNH một bản sao của header, và thư mục đó phải đứng TRƯỚC đường dẫn hệ
# thống trong -I. GCC tìm thấy bits/stdc++.h ở đây, rồi mới thấy .gch bên cạnh.
mkdir -p "${PCH_DIR}/bits"
cp "${HEADER}" "${PCH_DIR}/bits/stdc++.h"

echo "==> Dựng .gch (một phút, một lần cho mỗi host)"
# shellcheck disable=SC2086
g++ ${FLAGS} -x c++-header -o "${PCH_DIR}/bits/stdc++.h.gch" "${PCH_DIR}/bits/stdc++.h"
chmod -R a+rX "${PCH_DIR}"

echo "==> Xong: $(du -h "${PCH_DIR}/bits/stdc++.h.gch" | cut -f1) tại ${PCH_DIR}"
echo
echo "Kiểm PCH có THẬT SỰ được dùng không (dòng nào cũng phải có .gch):"
echo "  echo '#include <bits/stdc++.h>' | g++ ${FLAGS} -I${PCH_DIR} -H -x c++ -fsyntax-only - 2>&1 | head -3"
echo
echo "Worker tự gắn -I qua {pch} trong languages.compile_command; không cần sửa gì thêm."
