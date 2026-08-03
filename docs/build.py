#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Zone2Runner 인증 보고서 HTML 덱 빌더 (macOS/Linux/Windows 공통).

src/ 조각 + deck-style.html + arch/ 도식을 조립해 docs/에 완성 HTML을 쓴다.
링크 모드(기본): 도식을 docs/img/로 복사하고 img/<이름>.png?v=<md5 8자리>로 참조
— 파일명이 같아도 내용이 바뀌면 URL이 달라져 브라우저/Pages 캐시가 깨진다.
사용: python3 docs/build.py  (저장소 루트 또는 아무 데서나)
읽기/쓰기 인코딩을 UTF-8로 명시한다 — 플랫폼 기본값(Windows는 cp949)에 의존하면 한글에서 깨진다.
"""
import hashlib, json, os, re, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(HERE, 'src')
os.chdir(REPO)

cfg = json.load(open(f'{SRC}/decks.json', encoding='utf-8'))
deck_css = open(f'{SRC}/deck-style.html', encoding='utf-8').read()
nav_html = open(f'{SRC}/nav.html', encoding='utf-8').read().rstrip('\r\n')
NAV_CSS = '''<style>
  .topnav { position: sticky; top: 0; z-index: 50; background: var(--slide); border-bottom: 1px solid var(--line);
    box-shadow: var(--shadow); padding: 8px 14px; display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12.5px; }
  .topnav a { text-decoration: none; font-weight: 650; white-space: nowrap; }
  .topnav .brand { font-weight: 800; color: var(--ink); margin-right: 6px; }
  html { scroll-behavior: smooth; scroll-padding-top: 56px; }
  @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
</style>'''

os.makedirs(f'{HERE}/img', exist_ok=True)

def linked_src(key: str) -> str:
    rel = cfg['images'][key]
    base = os.path.basename(rel)
    shutil.copy2(rel, f'{HERE}/img/{base}')
    v = hashlib.md5(open(rel, 'rb').read()).hexdigest()[:8]
    return f'img/{base}?v={v}'

for d in cfg['decks']:
    body = open(f"{SRC}/{d['src']}", encoding='utf-8').read()
    for name, key in (d.get('placeholders') or {}).items():
        body = body.replace('data:image/png;base64,{{' + name + '}}', linked_src(key))
    if d.get('dataImg'):
        for k in sorted(set(re.findall(r'data-img="([a-z0-9-]+)"', body))):
            body = body.replace(f'<img data-img="{k}"', f'<img src="{linked_src(k)}" data-img="{k}"')
    head = ['<!doctype html>', '<html lang="ko">', '<head>', '<meta charset="utf-8">',
            '<meta name="viewport" content="width=device-width, initial-scale=1">',
            f"<title>{d['title']}</title>",
            '<style>*,*::before,*::after{box-sizing:border-box}img{max-width:100%;height:auto}</style>']
    if d.get('deckCss'): head.append(deck_css)
    if d.get('nav'): head.append(NAV_CSS)
    head += ['</head>', '<body>']
    if d.get('nav'): head.append(nav_html)
    html = '\n'.join(head) + '\n' + body + '\n\n</body>\n</html>\n'
    open(f"{HERE}/{d['out']}", 'w', encoding='utf-8', newline='\n').write(html)
    print(f"built docs/{d['out']}")
print('Done.')
