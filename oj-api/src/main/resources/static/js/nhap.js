const TIEN_TO = 'oj.nhap.';

function khoa(problemCode, languageCode) {
    return `${TIEN_TO}${problemCode}.${languageCode}`;
}

export function doc(problemCode, languageCode) {
    try {
        return localStorage.getItem(khoa(problemCode, languageCode)) || '';
    } catch {
        return '';
    }
}

export function luu(problemCode, languageCode, ma) {
    try {
        if (ma.trim()) localStorage.setItem(khoa(problemCode, languageCode), ma);
        else localStorage.removeItem(khoa(problemCode, languageCode));
    } catch {
    }
}

export function hoanLai(fn, cho = 800) {
    let hen;
    return (...args) => {
        clearTimeout(hen);
        hen = setTimeout(() => fn(...args), cho);
    };
}
