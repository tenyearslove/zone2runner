# connect-adb.ps1
# 무선 디버깅이 켜진 기기(워치/폰)를 mDNS로 자동 탐색해 전부 adb 연결한다.
# 사용법: .\scripts\connect-adb.ps1
# 전제: 각 기기는 최초 1회 페어링(adb pair)이 되어 있어야 한다.

$ErrorActionPreference = 'Continue'

adb start-server | Out-Null

# 1) mDNS로 무선 디버깅 기기 탐색 (2대 찾을 때까지 최대 5회 재시도, 결과 누적)
Write-Host "무선 디버깅 기기 탐색 중..." -ForegroundColor Cyan
$expected = 2  # 워치 + 폰
$endpoints = @()
for ($i = 1; $i -le 5; $i++) {
    $lines = adb mdns services
    $found = @($lines | Select-String '_adb-tls-connect' | ForEach-Object {
        if ("$_" -match '(\d{1,3}(?:\.\d{1,3}){3}:\d+)') { $Matches[1] }
    } | Where-Object { $_ })
    $endpoints = @($endpoints + $found | Sort-Object -Unique)
    if ($endpoints.Count -ge $expected) { break }
    Start-Sleep -Seconds 1
}

if ($endpoints.Count -eq 0) {
    Write-Host "mDNS로 새 기기를 찾지 못했다 (기기가 슬립이면 Wi-Fi가 꺼져 탐색이 안 됨)." -ForegroundColor Yellow
    Write-Host " - 워치/폰 화면을 깨운 뒤 다시 실행해보라"
    Write-Host " - [개발자 옵션 > 무선 디버깅]이 켜져 있는지, 같은 Wi-Fi인지, 최초 페어링(adb pair) 여부 확인"
} else {
    Write-Host "발견: $($endpoints.Count)대 ($($endpoints -join ', '))"
}
Write-Host ""

# 2) 이미 연결된 기기 파악
$connected = @(adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ("$_" -split '\s+')[0] })

# 3) 미연결 기기에 연결 시도
foreach ($ep in $endpoints) {
    if ($connected -contains $ep) {
        Write-Host "  $ep : 이미 연결됨" -ForegroundColor DarkGray
        continue
    }
    $result = adb connect $ep
    Write-Host "  $ep : $result"
}

# 4) 최종 연결 상태 + 기기 모델명 출력
Write-Host ""
Write-Host "=== 연결된 기기 ===" -ForegroundColor Cyan
$final = @(adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ("$_" -split '\s+')[0] })
foreach ($serial in $final) {
    $model = adb -s $serial shell getprop ro.product.model
    Write-Host ("  {0}  ({1})" -f $serial, "$model".Trim())
}
if ($final.Count -eq 0) {
    Write-Host "  없음 - 연결 실패. 기기 화면을 깨우고 무선 디버깅을 껐다 켠 뒤 다시 실행해보라." -ForegroundColor Yellow
    exit 1
}
