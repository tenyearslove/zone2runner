<#
  Zone2Runner certification report - HTML deck builder.

  Assembles src/ body fragments + deck-style.html + PNG diagrams under arch/
  into complete HTML pages (doctype + charset) that open directly in a browser.

  Default (linked mode) : diagrams are copied into docs/img/ and referenced as img/<name>.
                          Self-contained under docs/, so GitHub Pages serves everything.
  -Inline               : images embedded as base64, one self-contained file each.
                          Use this to publish or to send a deck outside the repo.

  Usage:
    powershell -File docs/build.ps1
    powershell -File docs/build.ps1 -Inline -OutDir C:\temp\zone2runner-deck

  Encoding note: this script is pure ASCII on purpose, so it parses correctly under
  both Windows PowerShell 5.1 and PowerShell 7 with no byte order mark. All Korean
  text lives in src/decks.json and src/nav.html, which are read as UTF-8 explicitly.
#>
[CmdletBinding()]
param(
  [switch]$Inline,
  [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..')).Path
if (-not $OutDir) { $OutDir = $here }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Linked mode copies each referenced diagram into docs/img/ and points at img/<basename>,
# so the docs/ folder is self-contained (GitHub Pages serves only docs/).
$sameDir = ((Resolve-Path $OutDir).Path.TrimEnd('\') -eq $here.TrimEnd('\'))
if (-not $Inline -and -not $sameDir) {
  throw 'Linked mode can only write to docs (img/ relative paths). Use -Inline for any other location.'
}
$imgDir = Join-Path $here 'img'
if (-not $Inline) { New-Item -ItemType Directory -Force -Path $imgDir | Out-Null }

$utf8 = New-Object System.Text.UTF8Encoding($false)
function Read-Utf8([string]$path) { return [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) }

$parts = @(
  @('01-intro.html', 'intro'), @('02-dp1.html', 'dp1'), @('03-dp2.html', 'dp2'),
  @('04-dp3.html', 'dp3'), @('05-dp4.html', 'dp4'), @('06-dp5.html', 'dp5'),
  @('07-arch.html', 'arch'), @('08-impl.html', 'impl'), @('09-appendix.html', 'appendix')
)
$fullParts = foreach ($part in $parts) {
  $body = (Read-Utf8 (Join-Path $here ('src\' + $part[0]))).Trim()
  $body.Replace('<div class="deck">', '<div class="deck" id="part-' + $part[1] + '">')
}
[IO.File]::WriteAllText((Join-Path $here 'src\10-full-report-parts.html'), ($fullParts -join "`r`n`r`n") + "`r`n", $utf8)

$cfg = Read-Utf8 (Join-Path $here 'src\decks.json') | ConvertFrom-Json
function Get-LinkedSrc([string]$key) {
  $rel = $cfg.images.$key
  $p = Join-Path $repo $rel
  if (-not (Test-Path $p)) { throw "Diagram not found: $p" }
  $base = Split-Path $rel -Leaf
  Copy-Item $p (Join-Path $imgDir $base) -Force
  $hash = (Get-FileHash -Algorithm MD5 -LiteralPath $p).Hash.Substring(0, 8).ToLowerInvariant()
  return 'img/' + $base + '?v=' + $hash
}

$deckCss = Read-Utf8 (Join-Path $here 'src\deck-style.html')
$navHtml = (Read-Utf8 (Join-Path $here 'src\nav.html')).TrimEnd("`r", "`n")

$navCss = @'
<style>
  .topnav { position: sticky; top: 0; z-index: 50; background: var(--slide); border-bottom: 1px solid var(--line);
    box-shadow: var(--shadow); padding: 8px 14px; display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12.5px; }
  .topnav a { text-decoration: none; font-weight: 650; white-space: nowrap; }
  .topnav .brand { font-weight: 800; color: var(--ink); margin-right: 6px; }
  html { scroll-behavior: smooth; scroll-padding-top: 56px; }
  @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
</style>
'@

$b64cache = @{}
function Get-B64([string]$key) {
  if (-not $b64cache.ContainsKey($key)) {
    $rel = $cfg.images.$key
    if (-not $rel) { throw "Unknown image key: $key" }
    $p = Join-Path $repo $rel
    if (-not (Test-Path $p)) { throw "Diagram not found: $p" }
    $b64cache[$key] = [Convert]::ToBase64String([IO.File]::ReadAllBytes($p))
  }
  return $b64cache[$key]
}

foreach ($d in $cfg.decks) {
  $body = Read-Utf8 (Join-Path $here ('src\' + $d.src))
  $tail = ''
  $count = 0

  # (1) {{NAME}} placeholders inside a data URI, e.g. src="data:image/png;base64,{{IMG1}}"
  if ($d.placeholders) {
    foreach ($prop in $d.placeholders.PSObject.Properties) {
      $key = $prop.Value
      $count++
      if ($Inline) {
        $body = $body.Replace('{{' + $prop.Name + '}}', (Get-B64 $key))
      } else {
        $body = $body.Replace('data:image/png;base64,{{' + $prop.Name + '}}', (Get-LinkedSrc $key))
      }
    }
  }

  # (2) <img data-img="key"> - inline mode resolves through a shared JS map so a
  #     diagram used on several slides is embedded once.
  if ($d.dataImg) {
    $keys = [regex]::Matches($body, 'data-img="([a-z0-9-]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $count += $keys.Count
    if ($Inline) {
      $entries = foreach ($k in $keys) { '"' + $k + '":"data:image/png;base64,' + (Get-B64 $k) + '"' }
      $tail = '<script>const IMGS={' + ($entries -join ',') + '};document.querySelectorAll("img[data-img]").forEach(i=>{i.src=IMGS[i.dataset.img];});</script>'
    } else {
      foreach ($k in $keys) {
        $body = $body.Replace('<img data-img="' + $k + '"', '<img src="' + (Get-LinkedSrc $k) + '" data-img="' + $k + '"')
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
  if ($d.nav) { $head += $navCss }
  $head += '</head>'
  $head += '<body>'
  if ($d.nav) { $head += $navHtml }

  $html = ($head -join "`n") + "`n" + $body + "`n" + $tail + "`n</body>`n</html>`n"
  $path = Join-Path $OutDir $d.out
  [IO.File]::WriteAllText($path, $html, $utf8)

  $mode = 'linked'
  if ($Inline) { $mode = 'inline' }
  '{0,-20} {1,9:N0} KB  {2}  {3} diagram(s)' -f $d.out, ((Get-Item $path).Length / 1KB), $mode, $count
}

'Done: {0}' -f $OutDir
