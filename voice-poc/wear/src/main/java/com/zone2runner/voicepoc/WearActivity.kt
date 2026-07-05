package com.zone2runner.voicepoc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * 음성 토크테스트 PoC (워치). 문장을 낭독 녹음 → ChannelClient로 폰에 PCM 전송 →
 * 폰이 판정(VoiceChannelService)한 결과(/voice/result)를 받아 표시한다.
 * 판단은 폰에서, 워치는 수집/전송/표시만.
 */
class WearActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private val recorder = AudioRecorder()
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var btn: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ensureMic()
    }

    override fun onResume() { super.onResume(); Wearable.getMessageClient(this).addListener(this) }
    override fun onPause() { super.onPause(); Wearable.getMessageClient(this).removeListener(this) }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_RESULT) return
        val parts = String(event.data).split("|")
        val label = parts.getOrElse(0) { "?" }
        val detail = parts.getOrElse(2) { "" }
        runOnUiThread { result.text = "$label\n$detail"; status.text = "결과 수신됨" }
    }

    private fun buildUi(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(BG); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(tv("문장 낭독", 15f, bold = true))
        col.addView(tv(SENTENCE, 12f, color = ACCENT).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(4), 0, dp(8)) })
        btn = Button(this).apply { text = "측정 → 폰"; isAllCaps = false; setOnClickListener { record() } }
        col.addView(btn)
        status = tv("대기", 11f, color = MUTED).also { it.setPadding(0, dp(6), 0, 0) }; col.addView(status)
        result = tv("결과 없음", 13f).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(6), 0, 0) }; col.addView(result)
        return col
    }

    private fun record() {
        if (busy) return
        if (!hasMic()) { ensureMic(); return }
        busy = true; btn.isEnabled = false; status.text = "녹음 중… 문장을 읽으세요"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val samples = recorder.record(RECORD_MS)
                sendToPhone(samples, recorder.sampleRate)
            }
            status.text = if (ok) "폰으로 전송함, 판정 대기…" else "폰 연결 없음"
            busy = false; btn.isEnabled = true
        }
    }

    /** ChannelClient로 폰에 PCM 스트리밍: int(sampleRate) + PCM16 LE. */
    private fun sendToPhone(samples: ShortArray, sampleRate: Int): Boolean = runCatching {
        val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
        val node = nodes.firstOrNull() ?: return false
        val client = Wearable.getChannelClient(this)
        val ch = Tasks.await(client.openChannel(node.id, PATH_AUDIO))
        val os = Tasks.await(client.getOutputStream(ch))
        DataOutputStream(os.buffered()).use { d -> d.writeInt(sampleRate); d.write(Pcm.toLeBytes(samples)); d.flush() }
        runCatching { Tasks.await(client.close(ch)) }
        true
    }.getOrDefault(false)

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ensureMic() { if (!hasMic()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1) }

    private fun tv(s: String, size: Float, bold: Boolean = false, color: Int = TEXT) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); gravity = Gravity.CENTER
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SENTENCE = "저는 지금 편안하게 천천히 달리고 있습니다"
        const val RECORD_MS = 4000
        const val PATH_AUDIO = "/voice/audio"
        const val PATH_RESULT = "/voice/result"
        val BG = Color.parseColor("#0E1116"); val TEXT = Color.parseColor("#E8EAED")
        val MUTED = Color.parseColor("#9AA0A6"); val ACCENT = Color.parseColor("#30D158")
    }
}
