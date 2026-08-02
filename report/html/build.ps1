<#
  Zone2Runner 인증 보고서 HTML 덱 빌드
  ------------------------------------
  src/ 의 본문 조각 + deck-style.html + arch/ 의 PNG 도식을 조립해
  브라우저에서 바로 열리는 완결 HTML(doctype/charset 포함)을 만든다.

  기본(링크 모드)  : 이미지를 상대경로로 참조 — 파일이 작아 git 이력에 적합. 저장소 안에서 열면 그대로 보인다.
  -Inline          : 이미지를 base64 로 심어 단일 파일로 만든다 — 저장소 밖으로 보내거나 Artifact 로 배포할 때.

  사용:
    pwsh -File report/html/build.ps1              # 링크 모드, report/html/ 에 출력(커밋 대상)
    pwsh -File report/html/build.ps1 -Inline -OutDir C:\temp\deck   # 자체 완결 단일 파일
#>
[CmdletBinding()]
param(
  [switch]$Inline,
  [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = Resolve-Path (Join-Path $here '..\..')
if (-not $OutDir) { $OutDir = $here }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 출력 파일에서 저장소 루트까지의 상대 접두사 (링크 모드 전용).
# 출력이 report/html 밖이면 링크가 깨지므로 그때는 -Inline 을 쓴다.
$prefix = '../../'
$sameDir = ((Resolve-Path $OutDir).Path.TrimEnd('\') -eq $here.TrimEnd('\'))
if (-not $Inline -and -not $sameDir) {
  throw "링크 모드는 report/html 로만 출력할 수 있다(상대경로 기준). 다른 위치로 뽑으려면 -Inline 을 쓴다."
}

# ── 도식 키 → 저장소 상대경로 ────────────────────────────────────────────
$IMAGES = [ordered]@{
  'cnc-simple'  = 'arch/diagrams/02b-component-cnc-simple.png'
  'cnc-detail'  = 'arch/diagrams/02-component-cnc.png'
  'context'     = 'arch/diagrams/01-context.png'
  'deployment'  = 'arch/diagrams/03-deployment.png'
  'module-view' = 'arch/diagrams/04-module-view.png'
  'usecase'     = 'arch/diagrams/05-usecase.png'
  'macro'       = 'arch/diagrams/06-final-architecture.png'
  'mod-a'       = 'arch/diagrams/06a-module-inference.png'
  'mod-bc'      = 'arch/diagrams/06b-module-explain-analysis.png'
  'mod-de'      = 'arch/diagrams/06c-module-operation-storage.png'
  'mod-f'       = 'arch/diagrams/06d-module-adaptation-verification.png'
  'dp1-counter' = 'arch/dp/dp-01-explainability/images/dp-01-explainability-counter-simple.png'
  'dp2-counter' = 'arch/dp/dp-02-controllability/images/dp-02-controllability-counter-simple.png'
  'dp3-counter' = 'arch/dp/dp-03-adaptability/images/dp-03-adaptability-counter-simple.png'
  'dp4-counter' = 'arch/dp/dp-04-robustness/images/dp-04-robustness-counter-simple.png'
  'dp5-counter' = 'arch/dp/dp-05-testability/images/dp-05-testability-counter-simple.png'
}

$b64cache = @{}
function Get-B64([string]$key) {
  if (-not $b64cache.ContainsKey($key)) {
    $p = Join-Path $repo $IMAGES[$key]
    if (-not (Test-Path $p)) { throw "도식 없음: $p" }
    $b64cache[$key] = [Convert]::ToBase64String([IO.File]::ReadAllBytes($p))
  }
  return $b64cache[$key]
}

# ── 덱 정의 ──────────────────────────────────────────────────────────────
# src      : src/ 안의 본문 조각
# deckCss  : 공용 deck-style.html 을 붙일지(인덱스는 자체 스타일)
# ph       : {{NAME}} 자리표시자 → 도식 키
# dataImg  : <img data-img="키"> 방식 사용
# nav      : 통합본 상단 고정 목차
$DECKS = @(
  @{ out='index.html';       src='00-index.html';              title='Zone2Runner 인증 보고서 — 문서 인덱스'; deckCss=$false }
  @{ out='01-intro.html';    src='01-intro.html';              title='과제 소개 / 요구사항 — Zone2Runner 인증 보고서'; deckCss=$true }
  @{ out='02-dp1.html';      src='02-dp1.html';                title='DP1 설명용이성 — Zone2Runner 인증 보고서 설계 파트'; deckCss=$true; dataImg=$true }
  @{ out='03-dp2.html';      src='03-dp2.html';                title='DP2 제어가능성 — Zone2Runner 인증 보고서 설계 파트'; deckCss=$true; ph=@{ 'IMG1'='cnc-simple'; 'IMG2'='dp2-counter' } }
  @{ out='04-dp3.html';      src='04-dp3.html';                title='DP3 기능적응성 — Zone2Runner 인증 보고서 설계 파트'; deckCss=$true; ph=@{ 'IMG1'='cnc-simple'; 'IMG2'='dp3-counter' } }
  @{ out='05-dp4.html';      src='05-dp4.html';                title='DP4 강건성 — Zone2Runner 인증 보고서 설계 파트'; deckCss=$true; ph=@{ 'IMG1'='cnc-simple'; 'IMG2'='dp4-counter' } }
  @{ out='06-dp5.html';      src='06-dp5.html';                title='DP5 테스트가능성 — Zone2Runner 인증 보고서 설계 파트'; deckCss=$true; ph=@{ 'IMG1'='cnc-simple'; 'IMG2'='dp5-counter' } }
  @{ out='07-arch.html';     src='07-arch.html';               title='최종 Architecture — Zone2Runner 인증 보고서 설계 파트'; deckCss=$true; ph=@{ 'IMG_MACRO'='macro'; 'IMG_A'='mod-a'; 'IMG_B'='mod-bc'; 'IMG_C'='mod-de'; 'IMG_D'='mod-f' } }
  @{ out='08-impl.html';     src='08-impl.html';               title='구현 / 품질속성 검증 / 결론 — Zone2Runner 인증 보고서'; deckCss=$true }
  @{ out='09-appendix.html'; src='09-appendix.html';           title='Appendix 뷰 — Use Case/Context/Module/C&C/Deployment'; deckCss=$true; ph=@{ 'IMG_UC'='usecase'; 'IMG_CTX'='context'; 'IMG_MOD'='module-view'; 'IMG_CNC'='cnc-simple'; 'IMG_CNC2'='cnc-detail'; 'IMG_DEP'='deployment' } }
  @{ out='full-report.html'; src='10-full-report-parts.html';  title='Zone2Runner 인증 보고서 — 전문 (통합본)'; deckCss=$true; dataImg=$true; nav=$true }
)

$NAV_CSS = @'
<style>
  .topnav { position: sticky; top: 0; z-index: 50; background: var(--slide); border-bottom: 1px solid var(--line);
    box-shadow: var(--shadow); padding: 8px 14px; display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12.5px; }
  .topnav a { text-decoration: none; font-weight: 650; white-space: nowrap; }
  .topnav .brand { font-weight: 800; color: var(--ink); margin-right: 6px; }
  html { scroll-behavior: smooth; scroll-padding-top: 56px; }
  @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
</style>
'@

$NAV_HTML = @'
<nav class="topnav">
  <span class="brand">Zone2Runner 보고서 전문</span>
  <a href="#part-intro">01 소개/02 요구</a>
  <a href="#part-dp1">DP1</a>
  <a href="#part-dp2">DP2</a>
  <a href="#part-dp3">DP3</a>
  <a href="#part-dp4">DP4</a>
  <a href="#part-dp5">DP5</a>
  <a href="#part-arch">최종 아키텍처</a>
  <a href="#part-impl">04 구현/검증 + 05 결론</a>
  <a href="#part-appendix">Appendix</a>
</nav>
'@

$deckCss = [IO.File]::ReadAllText((Join-Path $here 'src\deck-style.html'), [Text.Encoding]::UTF8)
$utf8 = New-Object System.Text.UTF8Encoding($false)

foreach ($d in $DECKS) {
  $body = [IO.File]::ReadAllText((Join-Path $here ('src\' + $d.src)), [Text.Encoding]::UTF8)
  $tail = ''
  $used = @()

  # (1) {{NAME}} 자리표시자
  if ($d.ph) {
    foreach ($name in $d.ph.Keys) {
      $key = $d.ph[$name]
      $used += $key
      if ($Inline) {
        $body = $body.Replace('{{' + $name + '}}', (Get-B64 $key))
      } else {
        $body = $body.Replace('data:image/png;base64,{{' + $name + '}}', ($prefix + $IMAGES[$key]))
      }
    }
  }

  # (2) <img data-img="키">
  if ($d.dataImg) {
    $keys = [regex]::Matches($body, 'data-img="([a-z0-9-]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $used += $keys
    if ($Inline) {
      $entries = foreach ($k in $keys) { '"' + $k + '":"data:image/png;base64,' + (Get-B64 $k) + '"' }
      $tail = '<script>const IMGS={' + ($entries -join ',') + '};document.querySelectorAll("img[data-img]").forEach(i=>{i.src=IMGS[i.dataset.img];});</script>'
    } else {
      foreach ($k in $keys) {
        $body = $body.Replace('<img data-img="' + $k + '"', '<img src="' + $prefix + $IMAGES[$k] + '" data-img="' + $k + '"')
      }
    }
  }

  $head = @()
  $head += '<!doctype html>'
  $head += '<html lang="ko">'
  $head += '<head>'
  $head += '<meta charset="utf-8">'
  $head += '<meta name="viewport" content="width=device-width, initial-scale=1">'
  $head += ('<title>' + $d.title + '</title>')
  $head += '<style>*,*::before,*::after{box-sizing:border-box}img{max-width:100%;height:auto}</style>'
  if ($d.deckCss) { $head += $deckCss }
  if ($d.nav) { $head += $NAV_CSS }
  $head += '</head>'
  $head += '<body>'
  if ($d.nav) { $head += $NAV_HTML }

  $html = ($head -join "`n") + "`n" + $body + "`n" + $tail + "`n</body>`n</html>`n"
  $path = Join-Path $OutDir $d.out
  [IO.File]::WriteAllText($path, $html, $utf8)

  $mode = 'linked'
  if ($Inline) { $mode = 'inline' }
  '{0,-20} {1,9:N0} KB  {2}  도식 {3}' -f $d.out, ((Get-Item $path).Length / 1KB), $mode, $used.Count
}

'완료: {0}' -f $OutDir
