package com.zone2runner.app.pipeline

/**
 * QA4 강건성: 생리 범위(40~220 bpm) 밖 HR을 이상치로 기각.
 * spec-003 이상값 가드. 특징/판정 이전에 적용해 튀는 값이 파이프라인을 오염시키지 않게 한다.
 */
object OutlierGuard {
    const val MIN_HR = 40
    const val MAX_HR = 220

    fun isValid(hr: Int): Boolean = hr in MIN_HR..MAX_HR

    /** 이상치면 직전 유효값으로 대체(없으면 null). */
    fun clean(hr: Int, lastValid: Int?): Int? = if (isValid(hr)) hr else lastValid
}
