# GuArDian 기기 DB 실시간 뷰어
#
# 폰(USB 연결)의 Room DB를 3초마다 꺼내 와 화면에 새로 그린다. Ctrl+C로 종료.
# 실행:  powershell -ExecutionPolicy Bypass -File scripts\db-watch.ps1
#
# 경로는 이 개발 PC(013) 기준으로 하드코딩돼 있다. 다른 PC에서는 위 두 변수만 고칠 것.
$adb = "C:\Users\013\android-tools\sdk\platform-tools\adb.exe"
$py  = "C:\Users\013\AppData\Local\Programs\Python\Python312\python.exe"

$tmp = Join-Path $env:TEMP "guardian-db-watch"
New-Item -ItemType Directory -Force $tmp | Out-Null

$dump = @'
import sqlite3, datetime, sys
con = sqlite3.connect(sys.argv[1])
def ts(ms): return datetime.datetime.fromtimestamp(ms/1000).strftime("%m-%d %H:%M")
print("=" * 78)
print(" GuArDian DB  ·", datetime.datetime.now().strftime("%H:%M:%S"), " (3초마다 갱신, Ctrl+C 종료)")
print("=" * 78)
print()
print("[ url_verdict — URL 위험 판정 ]")
rows = list(con.execute(
    "select riskLevel, reason, normalizedUrl, analyzedAt, validUntil "
    "from url_verdict order by analyzedAt desc"))
for r in rows:
    print(f"  [{r[0]:6}] {r[1]}")
    print(f"           {r[2][:72]}")
    print(f"           분석 {ts(r[3])} ~ 유효 {ts(r[4])}")
print(f"  총 {len(rows)}건")
print()
print("[ ad_fingerprint_link — 광고 지문 연계 ]")
links = list(con.execute(
    "select f.fingerprint, v.riskLevel, f.normalizedUrl, f.updatedAt "
    "from ad_fingerprint_link f "
    "left join url_verdict v on v.normalizedUrl = f.normalizedUrl "
    "order by f.updatedAt desc"))
for r in links:
    adv = " (광고주 지문)" if r[0].startswith("adv|") else ""
    print(f"  {r[0][:46]}{adv}  [{r[1]}]")
    print(f"      -> {r[2][:66]}  {ts(r[3])}")
print(f"  총 {len(links)}건")
print()
n = con.execute("select count(*) from ad_verdict").fetchone()[0]
print(f"[ ad_verdict — 광고 여부 캐시(Layer2) ]  {n}건")
'@
$dumpFile = Join-Path $tmp "dump.py"
Set-Content -Path $dumpFile -Value $dump -Encoding utf8

while ($true) {
    cmd /c "`"$adb`" exec-out run-as com.senioradguard cat databases/senior_ad_guard.db > `"$tmp\d.db`"" 2>$null
    cmd /c "`"$adb`" exec-out run-as com.senioradguard cat databases/senior_ad_guard.db-wal > `"$tmp\d.db-wal`"" 2>$null
    Clear-Host
    if ((Get-Item "$tmp\d.db" -ErrorAction SilentlyContinue).Length -gt 0) {
        & $py $dumpFile "$tmp\d.db"
    } else {
        "폰에서 DB를 읽지 못했습니다 — USB 연결과 앱 설치 상태를 확인하세요."
    }
    Start-Sleep 3
}
