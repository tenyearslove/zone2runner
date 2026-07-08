package com.zone2runner.app.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 시뮬 GPS 경로 생성기(시뮬 소스 공통). 기존 "상수 각속도 랜덤워크"는 지도에 원만 그려서
 * 그럴듯하지 않았다(사용자 요청 2026-07-08). 실제 도로 데이터를 따라가는 것은 범위 밖이므로,
 * 동네 러닝답게 보이는 형태만 만든다.
 *
 * - 직선 구간(20~80초) 유지 + 미세 흔들림(GPS 노이즈 느낌)
 * - 구간 끝에서 코너: 65%는 블록 코너(60~110도, 3~5초에 걸쳐 회전), 35%는 완만한 굽이(15~40도, 6~10초)
 * - 출발점에서 900m 이상 멀어지면 코너 방향을 귀환 쪽으로 편향 → 자연스러운 순환(루프) 경로
 *
 * 좌표 규약은 기존과 동일: heading 0=북, 동쪽(+lon)으로 증가, dLat=cos(h)/dLon=sin(h).
 * 결정성: 전달받은 rng만 사용(시드 재현 유지 — QA5 테스트가능성).
 */
class RouteWalk(
    private val startLat: Double,
    private val startLon: Double,
    private val rng: java.util.Random,
) {
    var lat = startLat; private set
    var lon = startLon; private set

    private var heading = rng.nextDouble() * 2 * PI
    private var straightLeft = 15 + rng.nextInt(40) // 첫 직선은 약간 짧게 — 초반부터 경로 형태가 드러나게
    private var turnLeft = 0
    private var turnRate = 0.0

    /** 1초(1샘플) 전진. mps = 초속(m/s). 정지 프레임은 호출하지 않으면 된다. */
    fun step(mps: Double) {
        if (turnLeft > 0) {
            heading += turnRate; turnLeft--
        } else if (--straightLeft <= 0) {
            beginTurn()
        }
        heading += rng.nextGaussian() * 0.008 // 직선 중 미세 흔들림(요철/GPS)
        lat += (mps * cos(heading)) / 111_320.0
        lon += (mps * sin(heading)) / (111_320.0 * cos(Math.toRadians(lat)))
    }

    private fun beginTurn() {
        val corner = rng.nextDouble() < 0.65
        val mag = if (corner) Math.toRadians(60.0 + rng.nextDouble() * 50.0)
        else Math.toRadians(15.0 + rng.nextDouble() * 25.0)
        var dir = if (rng.nextBoolean()) 1.0 else -1.0
        // 출발점에서 충분히 멀어졌으면 돌아오는 쪽으로 회전 방향 편향(순환 경로 형태)
        if (distFromStartM() > 900.0) {
            val toHome = atan2((startLon - lon) * cos(Math.toRadians(lat)), startLat - lat)
            val diff = normalizeAngle(toHome - heading)
            if (abs(diff) > 0.4) dir = if (diff > 0) 1.0 else -1.0
        }
        val durSec = if (corner) 3 + rng.nextInt(3) else 6 + rng.nextInt(5)
        turnRate = dir * mag / durSec
        turnLeft = durSec
        straightLeft = 20 + rng.nextInt(60)
    }

    private fun distFromStartM(): Double {
        val dLat = (lat - startLat) * 111_320.0
        val dLon = (lon - startLon) * 111_320.0 * cos(Math.toRadians(lat))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun normalizeAngle(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }
}
