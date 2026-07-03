"""필드 테스트 로그(spec-012 JSONL) 분석 — QA 매핑 리포트를 텍스트로 출력.

사용:
  ./.venv/bin/python ml/analyze_runlog.py <run-*.jsonl> [<run2.jsonl> ...]

회수:
  adb pull /sdcard/Android/data/com.zone2runner.app/files/runlogs ./fieldlogs

분석 항목 (spec-012 QA 매핑):
  1. 워치 HR 수신 품질: watchAgeMs 분포, 끊김(>3s) 구간 수/총시간  [adr-009, QA2]
  2. 이상치 가드: hrRaw != hrClean 빈도                          [QA2]
  3. 판정 안정성: 존 전환 횟수, 최소 체류시간, 채터링(<5s 체류)    [QA1]
  4. 개인화 궤적: uEst 시작→끝, 단조성                            [QA3]
  5. 코칭: 이벤트 수, LLM/규칙 추정(tookMs), 지연 분포             [adr-002/007]
  6. GPS 품질: 페이스 분산, 점프(1초 내 급변) 수                   [특징 품질]
"""
import json
import statistics
import sys


def load(path):
    meta, samples, events = None, [], []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            if o["type"] == "meta":
                meta = o
            elif o["type"] == "s":
                samples.append(o)
            else:
                events.append(o)
    return meta, samples, events


def fmt_sec(n):
    return "%d:%02d" % (n // 60, n % 60)


def analyze(path):
    meta, s, ev = load(path)
    print("=" * 70)
    print(f"파일: {path}")
    if not s:
        print("  샘플 없음 — 시작 직후 중단된 세션")
        return
    mode = meta.get("mode", "?") if meta else "?"
    prof = (meta or {}).get("profile", {})
    print(f"모드: {mode} | 프로필: {prof} | 모델 로드: {(meta or {}).get('model')}")
    dur = s[-1]["t"] - s[0]["t"] + 1
    print(f"샘플: {len(s)}개 / 경과 {fmt_sec(dur)} (결손 {dur - len(s)}초)")

    # 1. 워치 HR 수신 품질 (live 모드만 의미)
    ages = [x["watchAgeMs"] for x in s if x["watchAgeMs"] >= 0]
    if ages:
        gaps = [x for x in s if x["watchAgeMs"] > 3000]
        never = sum(1 for x in s if x["watchAgeMs"] < 0)
        print(f"\n[워치HR/QA2] 수신경과 중앙값 {statistics.median(ages):.0f}ms, "
              f"최대 {max(ages)}ms | 끊김(>3s) 샘플 {len(gaps)}개 | 미수신 {never}개")
        if gaps:
            runs, start = [], None
            for x in s:
                if x["watchAgeMs"] > 3000:
                    start = start if start is not None else x["t"]
                elif start is not None:
                    runs.append((start, x["t"])); start = None
            if start is not None:
                runs.append((start, s[-1]["t"]))
            worst = sorted(runs, key=lambda r: r[1] - r[0], reverse=True)[:5]
            print("  끊김 구간(상위):", ", ".join(f"{fmt_sec(a)}~{fmt_sec(b)}" for a, b in worst))
    else:
        print("\n[워치HR] watchAgeMs 없음(시뮬/목 모드)")

    # 2. 이상치 가드
    rejected = [x for x in s if x["hrRaw"] != x["hrClean"]]
    no_hr = [x for x in s if x["hrRaw"] <= 0]
    print(f"[가드/QA2] 원시!=정제 샘플 {len(rejected)}개 ({100*len(rejected)/len(s):.1f}%) | 원시 HR 무효(-1 등) {len(no_hr)}개")

    # 3. 판정 안정성
    judged = [x for x in s if x["judg"] >= 0]
    if judged:
        names = {0: "미달", 1: "유지", 2: "초과"}
        segs = []
        for x in judged:
            if not segs or segs[-1][0] != x["judg"]:
                segs.append([x["judg"], x["t"], x["t"]])
            else:
                segs[-1][2] = x["t"]
        chatter = [g for g in segs if g[2] - g[1] < 5]
        stay = {k: sum(1 for x in judged if x["judg"] == k) for k in (0, 1, 2)}
        print(f"[판정/QA1] 판정샘플 {len(judged)}개 | 체류 "
              + " / ".join(f"{names[k]} {100*v/len(judged):.0f}%" for k, v in stay.items())
              + f" | 존 전환 {len(segs)-1}회, 채터링(<5s 세그먼트) {len(chatter)}개")

    # 4. 개인화 궤적
    u0, u1 = s[0]["uEst"], s[-1]["uEst"]
    us = [x["uEst"] for x in s]
    print(f"[개인화/QA3] uEst {u0:.4f} → {u1:.4f} (Δ{u1-u0:+.4f}), min {min(us):.4f} max {max(us):.4f}")

    # 5. 코칭
    coach = [e for e in ev if e.get("kind") == "coach"]
    if coach:
        took = [c["tookMs"] for c in coach]
        llm = [t for t in took if t > 300]  # 휴리스틱: 규칙은 수 ms, LLM은 수백 ms 이상
        print(f"[코칭/adr-002] {len(coach)}건 | LLM 추정 {len(llm)}건 "
              f"(지연 중앙값 {statistics.median(llm) if llm else 0:.0f}ms, 최대 {max(llm) if llm else 0}ms) "
              f"| 규칙 추정 {len(took)-len(llm)}건")
        for c in coach:
            src = "llm " if c["tookMs"] > 300 else "rule"
            print(f"    [{fmt_sec(c.get('t', 0))}] ({src} {c['tookMs']:>5}ms) {c['text']}")
    else:
        print("[코칭] 이벤트 없음")

    # 6. GPS/페이스 품질
    paces = [x["pace"] for x in s if 0.1 < x["pace"] < 30]
    if len(paces) > 10:
        jumps = sum(1 for a, b in zip(paces, paces[1:]) if abs(b - a) > 2.0)
        print(f"[GPS] 페이스 중앙값 {statistics.median(paces):.2f}분/km, 표준편차 {statistics.pstdev(paces):.2f} "
              f"| 1초 급변(>2분/km) {jumps}회")

    end = [e for e in ev if e.get("kind") == "end"]
    if end:
        print(f"[종료] {end[0]}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    for p in sys.argv[1:]:
        analyze(p)
