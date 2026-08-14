# GuArDian 기기 DB 덤프 — 콘솔 텍스트 + 자동 갱신 HTML 생성
# 사용: python db_dump.py <db경로> [html출력경로]
import sqlite3, datetime, sys, html

db_path = sys.argv[1]
html_out = sys.argv[2] if len(sys.argv) > 2 else None
con = sqlite3.connect(db_path)


def ts(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%m-%d %H:%M")


now = datetime.datetime.now().strftime("%H:%M:%S")
verdicts = list(con.execute(
    "select riskLevel, reason, normalizedUrl, finalUrl, analyzedAt, validUntil "
    "from url_verdict order by analyzedAt desc"))
links = list(con.execute(
    "select f.fingerprint, v.riskLevel, f.normalizedUrl, f.updatedAt "
    "from ad_fingerprint_link f "
    "left join url_verdict v on v.normalizedUrl = f.normalizedUrl "
    "order by f.updatedAt desc"))
l2 = con.execute("select count(*) from ad_verdict").fetchone()[0]

# ── 콘솔 ──
print("=" * 78)
print(f" GuArDian DB · {now}  (3초마다 갱신, Ctrl+C 종료)")
print("=" * 78)
print(f"\n[ url_verdict — URL 위험 판정 ]  {len(verdicts)}건")
for r in verdicts:
    print(f"  [{r[0]:6}] {r[1]}")
    print(f"           {r[2][:72]}")
    print(f"           분석 {ts(r[4])} ~ 유효 {ts(r[5])}")
print(f"\n[ ad_fingerprint_link — 광고 지문 연계 ]  {len(links)}건")
for r in links:
    adv = " (광고주 지문)" if r[0].startswith("adv|") else ""
    print(f"  {r[0][:46]}{adv}  [{r[1]}]")
    print(f"      -> {r[2][:66]}  {ts(r[3])}")
print(f"\n[ ad_verdict — 광고 여부 캐시(Layer2) ]  {l2}건")

# ── HTML (브라우저가 3초마다 스스로 새로 고침) ──
if html_out:
    color = {"LOW": "#2E7D32", "MEDIUM": "#B8860B", "HIGH": "#C62828", None: "#666"}
    badge = {"LOW": "저위험", "MEDIUM": "중위험", "HIGH": "고위험", None: "판정 없음"}

    def row_v(r):
        c = color.get(r[0], "#666")
        return (f'<tr><td><b style="color:{c}">{badge.get(r[0], r[0])}</b></td>'
                f'<td>{html.escape(r[1])}<div class="u">{html.escape(r[2])}</div></td>'
                f'<td class="t">{ts(r[4])}<br>~{ts(r[5])}</td></tr>')

    def row_l(r):
        c = color.get(r[1], "#666")
        kind = "광고주" if r[0].startswith("adv|") else "카드"
        return (f'<tr><td class="fp">{html.escape(r[0][:40])}…<div class="u">{kind} 지문</div></td>'
                f'<td><b style="color:{c}">{badge.get(r[1], r[1])}</b>'
                f'<div class="u">{html.escape(r[2][:70])}</div></td>'
                f'<td class="t">{ts(r[3])}</td></tr>')

    page = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="3">
<title>GuArDian DB</title>
<style>
 body{{font-family:'Malgun Gothic',sans-serif;margin:16px;background:#fafafa}}
 h2{{margin:18px 0 6px}} .u{{color:#888;font-size:12px;word-break:break-all}}
 table{{border-collapse:collapse;width:100%;background:#fff}}
 td{{border:1px solid #e0e0e0;padding:6px 10px;vertical-align:top;font-size:14px}}
 .t{{white-space:nowrap;color:#555;font-size:12px}} .fp{{font-family:monospace;font-size:12px}}
 .meta{{color:#666}}
</style></head><body>
<h1>GuArDian 기기 DB <span class="meta" style="font-size:14px">· {now} · 3초마다 자동 갱신</span></h1>
<h2>URL 위험 판정 ({len(verdicts)}건)</h2>
<table>{''.join(row_v(r) for r in verdicts)}</table>
<h2>광고 지문 연계 ({len(links)}건)</h2>
<table>{''.join(row_l(r) for r in links)}</table>
<p class="meta">광고 여부 캐시(Layer2): {l2}건 · 폰이 USB로 연결돼 있어야 갱신됩니다</p>
</body></html>"""
    with open(html_out, "w", encoding="utf-8") as f:
        f.write(page)
