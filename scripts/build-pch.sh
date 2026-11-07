#!/usr/bin/env bash
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

mkdir -p "${PCH_DIR}/bits"
cp "${HEADER}" "${PCH_DIR}/bits/stdc++.h"

echo "==> Dựng .gch (một phút, một lần cho mỗi host)"
g++ ${FLAGS} -x c++-header -o "${PCH_DIR}/bits/stdc++.h.gch" "${PCH_DIR}/bits/stdc++.h"
chmod -R a+rX "${PCH_DIR}"

echo "==> Xong: $(du -h "${PCH_DIR}/bits/stdc++.h.gch" | cut -f1) tại ${PCH_DIR}"
echo
echo "Kiểm PCH có THẬT SỰ được dùng không (dòng nào cũng phải có .gch):"
echo "  echo '#include <bits/stdc++.h>' | g++ ${FLAGS} -I${PCH_DIR} -H -x c++ -fsyntax-only - 2>&1 | head -3"
echo
echo "Worker tự gắn -I qua {pch} trong languages.compile_command; không cần sửa gì thêm."
