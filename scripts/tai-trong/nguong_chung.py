import io
import json
import os

THU_MUC = os.path.dirname(os.path.abspath(__file__))

def nap_nguong():
    with io.open(os.path.join(THU_MUC, 'nguong.json'), encoding='utf-8') as fh:
        return json.load(fh)

def doc_so(metrics, ten, thong_ke):
    m = metrics.get(ten)
    if not m or 'values' not in m:
        return None
    v = m['values']
    if v.get('count') == 0 or (v.get('passes') == 0 and v.get('fails') == 0):
        return None
    return v.get(thong_ke)

def ap_dung(nhom, kich_ban, nguon=None):
    return [d for d in nhom if kich_ban in d['kich_ban'] and (nguon is None or d['nguon'] == nguon)]

def dinh_dang(v, don_vi):
    if v is None:
        return '—'
    if don_vi == 'ti_le':
        return f'{v * 100:.2f} %'
    if don_vi == 'ms':
        return f'{v:,.0f} ms'.replace(',', ' ')
    if don_vi == 'bai_s':
        return f'{v:.2f} bài/s'
    if don_vi == 'mbps':
        return f'{v:.1f} Mbps'
    return f'{v:,.0f}'.replace(',', ' ')

def cham(metrics, dong):
    v = doc_so(metrics, dong['metric'], dong['thong_ke'])
    return v, (None if v is None else v <= dong['toi_da'])
