package com.pleos.explorer.domain.model

data class AchievementTitle(
    val titleId: String,
    val conditionText: String,
    val acquiredAtMs: Long,
    val tripId: String?,
)

sealed interface AchievementFact {
    data class BadgeGranted(val badgeId: String, val tripId: String?, val atMs: Long) : AchievementFact
    data class GhostFinished(val result: GhostResult, val atMs: Long) : AchievementFact
}

data class AchievementProgress(
    val regionBadgeCount: Int = 0,
    val acquiredTitleIds: Set<String> = emptySet(),
)

data class TitleQualified(
    val titleId: String,
    val tripId: String?,
    val acquiredAtMs: Long,
    val conditionText: String,
)

data class TitleSnapshot(val revision: Long = 0, val progress: AchievementProgress = AchievementProgress())

data class TitleUpdate(
    val snapshot: TitleSnapshot,
    val titles: List<AchievementTitle> = emptyList(),
    val rewards: List<PendingReward> = emptyList(),
)
