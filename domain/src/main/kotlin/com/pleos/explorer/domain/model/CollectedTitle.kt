package com.pleos.explorer.domain.model

data class CollectedTitle(val title: AchievementTitle, val inputSource: String, val acknowledged: Boolean = false) {
    val key: String get() = "$inputSource:${title.titleId}"
    val isReplay: Boolean get() = inputSource == "REPLAY"
}
