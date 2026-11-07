import { DUONG } from './duong-dan.js';

const KHOA_PHIEN = 'oj.phien';

export function phien() {
    try {
        return JSON.parse(localStorage.getItem(KHOA_PHIEN) || 'null');
    } catch {
        return null;
    }
}

export function luuPhien(p) {
    localStorage.setItem(KHOA_PHIEN, JSON.stringify(p));
}

export function xoaPhien() {
    localStorage.removeItem(KHOA_PHIEN);
}

export class LoiApi extends Error {
    constructor(status, code, message, retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }
}

async function doc(res) {
    if (res.status === 204) return null;
    const kieu = res.headers.get('content-type') || '';
    if (!kieu.includes('json')) return null;
    return res.json().catch(() => null);
}

const KHOA_LAM_MOI = 'oj.lam-moi-phien';
let lamMoiDangChay = null;

function lamMoi(accessTokenBiTuChoi) {
    if (navigator.locks) {
        return navigator.locks.request(KHOA_LAM_MOI, () => lamMoiMotLan(accessTokenBiTuChoi));
    }
    lamMoiDangChay ??= lamMoiMotLan(accessTokenBiTuChoi)
        .finally(() => { lamMoiDangChay = null; });
    return lamMoiDangChay;
}

async function lamMoiMotLan(accessTokenBiTuChoi) {
    const p = phien();
    if (!p?.refreshToken) return false;
    if (p.accessToken && p.accessToken !== accessTokenBiTuChoi) return true;
    const res = await fetch(DUONG.auth.lamMoi, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: p.refreshToken }),
    });
    if (!res.ok) {
        xoaPhien();
        return false;
    }
    luuPhien(await res.json());
    return true;
}

export async function goi(duongDan, tuyChon = {}) {
    const p = phien();
    const headers = { ...(tuyChon.headers || {}) };
    if (p?.accessToken) headers.Authorization = `Bearer ${p.accessToken}`;
    if (tuyChon.body !== undefined && !(tuyChon.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }

    const res = await fetch(duongDan, {
        method: tuyChon.method || 'GET',
        headers,
        body: tuyChon.body instanceof FormData
            ? tuyChon.body
            : (tuyChon.body !== undefined ? JSON.stringify(tuyChon.body) : undefined),
    });

    if (res.ok) return doc(res);

    const than = await doc(res);
    const code = than?.code;

    if (code === 'auth.token_het_han' && !tuyChon.khongLamMoi) {
        if (await lamMoi(p?.accessToken)) {
            return goi(duongDan, { ...tuyChon, khongLamMoi: true });
        }
    }

    throw new LoiApi(
        res.status,
        code,
        than?.message || 'Có lỗi xảy ra. Thử lại sau.',
        res.headers.get('Retry-After'));
}

export function accessToken() {
    return phien()?.accessToken || null;
}
