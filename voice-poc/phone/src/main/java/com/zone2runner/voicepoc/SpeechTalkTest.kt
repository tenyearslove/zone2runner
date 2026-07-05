package com.zone2runner.voicepoc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 온디바이스 ASR 낭독 채점(폰). SpeechRecognizer로 낭독을 전사해 "목표 문장을 어디까지 읽었나"
 * (완성도)를 직접 측정하고, onRmsChanged 엔벨로프로 호흡 끊김을 근사한다. 완전 온디바이스(오프라인 우선).
 *
 * 마이크 제약: SpeechRecognizer가 마이크를 점유해 raw PCM은 못 얻는다(피치 분석 불가). 대신 ASR이
 * "헉헉"(가쁜 숨)을 단어로 전사하지 않으므로, 발화 내용 기준의 완성도가 숨소리 혼입을 자연히 배제한다.
 */
class SpeechTalkTest(private val ctx: Context) {

    data class Result(val transcript: String, val rms: List<Float>, val error: String?)

    private var recognizer: SpeechRecognizer? = null
    private val rms = ArrayList<Float>()
    private var partial = ""
    private var done: ((Result) -> Unit)? = null

    fun start(onDone: (Result) -> Unit) {
        done = onDone; rms.clear(); partial = ""
        val sr = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) { rms.add(rmsdB) }
            override fun onPartialResults(b: Bundle) { pick(b)?.let { partial = it } }
            override fun onResults(b: Bundle) { pick(b)?.let { partial = it }; finish(null) }
            override fun onError(error: Int) { finish(errText(error)) } // NO_MATCH여도 partial 사용
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        sr.startListening(intent)
    }

    /** 사용자가 낭독을 마쳤을 때 명시적으로 종료(무음 대기 없이 즉시 결과). */
    fun stop() { recognizer?.stopListening() }

    private fun pick(b: Bundle): String? =
        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun finish(err: String?) {
        val r = Result(partial.trim(), rms.toList(), if (partial.isBlank()) err else null)
        recognizer?.destroy(); recognizer = null
        done?.invoke(r); done = null
    }

    private fun errText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "인식된 말 없음"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리 시간 초과"
        SpeechRecognizer.ERROR_AUDIO -> "오디오 오류"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한 필요"
        else -> "ASR 오류($code)"
    }
}
