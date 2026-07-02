package com.zone2runner.app.ui

import com.zone2runner.app.domain.RunReport

/** MainActivity → ReportActivity 리포트 전달용(직렬화 회피). */
object ReportHolder {
    var last: RunReport? = null
}
