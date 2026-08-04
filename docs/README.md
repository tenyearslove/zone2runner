# Report HTML — 인증 보고서 화면용 덱

AI Specialist 설계 과제의 제출용 HTML 보고서다. 브라우저에서 검토하고, 같은 HTML을 16:9 PDF로 인쇄하거나 자체 완결 단일 파일로 만들어 공유한다.

**제출 품질의 기준은 `docs/src/` HTML 원본과 빌드 결과다.** `report/`, `spec/`, `docs/review/`의 Markdown은 근거와 작업 결정을 보존하는 보조 문서다. 내용이나 논리를 바꾸면 관련 보조 문서도 함께 확인해 다시 모순이 생기지 않게 한다.

## 파일

| 파일 | 파트 | 대응 원고 |
|---|---|---|
| `index.html` | 전체 문서 인덱스 | — |
| `01-intro.html` | 01 과제 소개 + 02 요구사항 | report-018 |
| `02-dp1.html` ~ `04-dp3.html` | 03 설계 — DP1 기능적응성 / DP2 설명용이성 / DP3 제어가능성 | report-010 / report-008 / report-009 |
| `07-arch.html` | 03 설계 — 최종 Architecture(거시 + 모듈 4장) | report-013 |
| `08-impl.html` | 04 구현 + 검증 + 05 결론 | report-014, 015, 016 |
| `09-appendix.html` | Appendix 뷰 5종 | report-017 |
| `full-report.html` | 전 파트 통합본(상단 고정 목차) | 위 전부 |

`src/` = 조립 재료. 본문 조각과 공용 CSS(`deck-style.html`) 외에, 덱 목록과 제목과 도식 대응을 담은 `decks.json`, 통합본 상단 목차 `nav.html` 이 있다. `build.ps1` = 조립 스크립트.

## 인코딩 — 전부 UTF-8, BOM 없음

산출물 HTML, `src/` 의 모든 파일, `decks.json` 이 전부 **BOM 없는 UTF-8**이다. HTML은 `<meta charset="utf-8">` 를 달고 나가므로 브라우저가 로컬 파일로 열어도 한글이 깨지지 않는다.

`build.ps1` 만 사정이 다르다. Windows PowerShell 5.1 은 BOM 없는 `.ps1` 을 시스템 ANSI 코드페이지로 읽어서, 스크립트 안에 한글이 있으면 파싱 단계에서 깨진다. 그래서 **스크립트 본문은 순수 ASCII로 두고, 한글은 전부 `decks.json` 과 `nav.html` 로 옮겼다.** 이 데이터 파일은 스크립트가 UTF-8로 명시해 읽는다(`[IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)`). 결과적으로 BOM을 붙일 이유가 사라져서, 5.1과 PowerShell 7 어느 쪽에서도 그대로 돌아간다.

덱 제목을 바꾸거나 목차 문구를 손볼 때는 `decks.json` / `nav.html` 을 고친다. **`build.ps1` 에는 한글을 넣지 않는다** — 넣는 순간 5.1에서 깨진다.

## 저장 형태 — 이미지는 링크, 필요할 때 심는다

커밋된 HTML은 도식을 상대경로로 참조한다(`../../arch/diagrams/*.png`). 저장소를 clone 한 뒤 파일을 열면 도식까지 그대로 보이고, 파일 하나가 10~120KB라 변경 이력이 읽힌다.

도식을 base64 로 심은 자체 완결 파일은 필요할 때 만든다 — 저장소 밖으로 보내거나 Artifact 로 배포할 때다. 같은 내용이 6.4MB가 되고 갱신할 때마다 그만큼 이력에 쌓이므로 커밋하지 않는다.

```powershell
# 링크 모드(커밋 대상) — docs/ 에 덮어쓴다(도식은 img/로 복사, ?v=해시 캐시버스터 자동)
python3 docs/build.py          # macOS/Linux 정본 빌더
powershell -File docs/build.ps1

# 자체 완결 단일 파일 — 저장소 밖 아무 폴더로
powershell -File docs/build.ps1 -Inline -OutDir C:\temp\zone2runner-deck
```

빌드는 결정적이다. 같은 소스와 같은 PNG면 같은 결과가 나오므로, 도식을 다시 렌더한 뒤에는 빌드를 다시 돌려 커밋한다.

## 고칠 때

1. 관련 원고 md가 있으면 근거와 설명을 함께 고친다.
2. `src/` 의 대응 본문을 고친다. `10-full-report-parts.html`은 빌드할 때 01~09 원본에서 자동 생성되므로 직접 편집하지 않는다.
3. `build.ps1` 을 돌리고 결과를 커밋한다.

도식은 `<img data-img="키">`로 지정하고, 키와 PNG 경로의 대응은 `src/decks.json`에서 관리한다. 빌더는 이전 `{{IMG1}}` 자리표시자도 호환하지만 새 문서에는 `data-img` 방식을 사용한다. 덱을 추가할 때는 `decks.json`에 항목을 더한다.

## 배포 (GitHub Pages) 와 구 Artifact 채널

같은 내용을 웹에서 볼 수 있게 배포한 주소. 갱신하면 같은 URL로 다시 배포된다.

| 덱 | URL |
|---|---|
| 통합본 | `full-report.html` |
| 인덱스 | `index.html` |
| 01 소개/요구 | `01-intro.html` |
| DP1 기능적응성 | `02-dp1.html` |
| DP2 설명용이성 | `03-dp2.html` |
| DP3 제어가능성 | `04-dp3.html` |
| 최종 아키텍처 | `07-arch.html` |
| 구현/검증/결론 | `08-impl.html` |
| Appendix 뷰 | `09-appendix.html` |

정본 배포는 **GitHub Pages**(저장소 설정에서 Source = main 브랜치 `/docs`)다 — 이 폴더가 그대로 사이트가 되고, 진입점은 `index.html`. 표의 파일명은 이 폴더 안 페이지들이다. 과거 claude.ai Artifact 채널은 폐기했다(같은 내용의 옛 배포본이 남아 있을 수 있으나 갱신되지 않음). 항상 `src/` 를 고쳐 다시 빌드한 것을 커밋한다.
