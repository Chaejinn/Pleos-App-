package com.pleos.explorer.domain.usecase

import com.pleos.explorer.domain.engine.TitleEvaluator
import com.pleos.explorer.domain.model.AchievementFact
import com.pleos.explorer.domain.model.AchievementTitle
import com.pleos.explorer.domain.model.PendingReward
import com.pleos.explorer.domain.model.RewardKind
import com.pleos.explorer.domain.model.TitleUpdate
import com.pleos.explorer.domain.port.IdGenerator
import com.pleos.explorer.domain.port.TitleStore

class ProcessTitleFactUseCase(
    private val store: TitleStore,
    private val ids: IdGenerator,
    private val evaluator: TitleEvaluator = TitleEvaluator(),
) {
    suspend fun process(fact: AchievementFact): TitleUpdate {
        val state = store.load()
        require(state.revision >= 0)
        val qualified = evaluator.evaluate(fact, state.progress)
        if (qualified.isEmpty()) return TitleUpdate(state)
        val titles = qualified.map { AchievementTitle(it.titleId, it.conditionText, it.acquiredAtMs, it.tripId) }
        val rewards = titles.map { title ->
            val id = ids.newId()
            check(id.isNotBlank())
            PendingReward(id, "TITLE:${title.titleId}", title.tripId, RewardKind.TITLE, title.titleId, title.acquiredAtMs)
        }
        val progress = state.progress.copy(acquiredTitleIds = state.progress.acquiredTitleIds + titles.map { it.titleId })
        val update = TitleUpdate(state.copy(revision = Math.addExact(state.revision, 1), progress = progress), titles, rewards)
        store.commit(state.revision, update)
        return update
    }
}
