# Report HTML — 인증 보고서 화면용 덱

보고서 원고(`report/report-0NN-*.md`)를 슬라이드 형태로 렌더한 HTML. 검토용으로 브라우저에서 바로 열어 보거나, 자체 완결 단일 파일로 뽑아 공유한다.

**정본은 md 원고다.** 이 폴더의 HTML은 그 표현물이므로, 내용을 고칠 때는 원고 md와 `src/` 본문을 함께 고친다.

## 파일

| 파일 | 파트 | 대응 원고 |
|---|---|---|
| `index.html` | 전체 문서 인덱스 | — |
| `01-intro.html` | 01 과제 소개 + 02 요구사항 | report-018 |
| `02-dp1.html` ~ `06-dp5.html` | 03 설계 — DP1 설명용이성 / DP2 제어가능성 / DP3 기능적응성 / DP4 강건성 / DP5 테스트가능성 | report-008 ~ 012 |
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
# 링크 모드(커밋 대상) — report/html/ 에 덮어쓴다
powershell -File report/html/build.ps1

# 자체 완결 단일 파일 — 저장소 밖 아무 폴더로
powershell -File report/html/build.ps1 -Inline -OutDir C:\temp\zone2runner-deck
```

빌드는 결정적이다. 같은 소스와 같은 PNG면 같은 결과가 나오므로, 도식을 다시 렌더한 뒤에는 빌드를 다시 돌려 커밋한다.

## 고칠 때

1. 원고 md를 고친다(정본).
2. `src/` 의 대응 본문을 같은 내용으로 고친다. 통합본은 `src/10-full-report-parts.html` 에 각 파트가 `id="part-*"` 앵커와 함께 이어져 있어, 개별 덱과 통합본 두 곳을 모두 고쳐야 한다.
3. `build.ps1` 을 돌리고 결과를 커밋한다.

도식 자리표시자는 두 가지다 — `{{IMG1}}` 처럼 이름을 직접 쓰는 방식(DP2~5, 아키텍처, Appendix)과 `<img data-img="키">` 방식(DP1, 통합본). 키와 PNG 경로의 대응, 그리고 덱마다 어떤 자리표시자를 쓰는지는 `src/decks.json` 에 있다. 덱을 새로 추가할 때도 `decks.json` 에 항목 하나를 더하면 되고 스크립트는 손대지 않는다.

## Artifact 배포본

같은 내용을 웹에서 볼 수 있게 배포한 주소. 갱신하면 같은 URL로 다시 배포된다.

| 덱 | URL |
|---|---|
| 통합본 | https://claude.ai/code/artifact/82597934-14b2-49d5-80dd-f9b6dfc9c04e |
| 인덱스 | https://claude.ai/code/artifact/2375aee0-4163-42c3-9867-2ff0f653b08e |
| 01 소개/요구 | https://claude.ai/code/artifact/9e41e1a3-4498-4a7f-88ac-2577cf39ae47 |
| DP1 | https://claude.ai/code/artifact/fba06e57-0273-428f-8829-43ed6c1c7c5b |
| DP2 | https://claude.ai/code/artifact/782d02bb-b769-43c9-8ef6-a8496ac9e9b7 |
| DP3 | https://claude.ai/code/artifact/bb523471-9a76-4a95-aa4e-f116d0dc6e13 |
| DP4 | https://claude.ai/code/artifact/4bfb5619-0d1d-47ad-b838-30ebec0e0776 |
| DP5 | https://claude.ai/code/artifact/b4d306e0-d274-46fe-9d74-5136cb1f3dba |
| 최종 아키텍처 | https://claude.ai/code/artifact/56da277d-24d0-4430-859c-5308c7ab8281 |
| 구현/검증/결론 | https://claude.ai/code/artifact/ded1a065-554f-4d59-a30e-071f1b076208 |
| Appendix 뷰 | https://claude.ai/code/artifact/9e8ad40f-e2cd-4542-84e9-da8f44ba4dfc |

Artifact 는 `-Inline` 산출물을 올린 것이라 이 폴더의 소스와 같은 내용이다. 배포본만 고치고 저장소를 안 고치면 다음 빌드에서 되돌아가므로, 항상 `src/` 를 고쳐 다시 빌드한 것을 올린다.
