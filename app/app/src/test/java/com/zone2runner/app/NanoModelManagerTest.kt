package com.zone2runner.app

import com.google.mlkit.genai.common.FeatureStatus
import com.zone2runner.app.coaching.NanoModelManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nano 모델 상태 매핑(adr-027) — ML Kit 코드 → 상태/라벨. 미지 코드는 UNAVAILABLE(정직 폴백). */
class NanoModelManagerTest {

    @Test fun mapStatus_knownCodes() {
        assertEquals(NanoModelManager.State.AVAILABLE, NanoModelManager.mapStatus(FeatureStatus.AVAILABLE))
        assertEquals(NanoModelManager.State.DOWNLOADABLE, NanoModelManager.mapStatus(FeatureStatus.DOWNLOADABLE))
        assertEquals(NanoModelManager.State.DOWNLOADING, NanoModelManager.mapStatus(FeatureStatus.DOWNLOADING))
        assertEquals(NanoModelManager.State.UNAVAILABLE, NanoModelManager.mapStatus(FeatureStatus.UNAVAILABLE))
    }

    @Test fun mapStatus_unknownCode_isUnavailable() {
        assertEquals(NanoModelManager.State.UNAVAILABLE, NanoModelManager.mapStatus(-999))
    }

    @Test fun stateLabels_forSettingsUi() {
        assertEquals("사용 가능", NanoModelManager.stateLabel(NanoModelManager.State.AVAILABLE))
        assertEquals("다운로드 필요", NanoModelManager.stateLabel(NanoModelManager.State.DOWNLOADABLE))
    }
}
