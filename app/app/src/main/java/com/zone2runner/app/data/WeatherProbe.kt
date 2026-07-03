package com.zone2runner.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 현재 기온 1회 조회 (Open-Meteo, 키 불필요) — 러닝 대시보드 참고 표시용.
 * 주의: 기온은 판정 특징(MLP 입력)이 아니다(adr-008에서 날씨 제외). 더위가 Cardiac Drift를
 * 키우는 만큼 사용자 참고 정보로만 노출하고, 특징 편입은 필드 데이터 검토 후 결정(향후 과제).
 * 실패(오프라인 등) 시 null — 러닝에 영향 없음.
 */
object WeatherProbe {

    suspend fun currentTempC(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m"
                    .format(lat, lon)
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            try {
                conn.inputStream.bufferedReader().use { r ->
                    JSONObject(r.readText()).getJSONObject("current").getDouble("temperature_2m")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
