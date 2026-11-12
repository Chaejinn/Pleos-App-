package com.pleos.explorer.platform.speech

class GatedSpeaker(
    private val allowed: () -> Boolean,
    private val createSpeaker: () -> Speaker,
) : Speaker {
    private var delegate: Speaker? = null

    override fun speak(text: String) {
        if (!allowed()) return
        val speaker = delegate ?: createSpeaker().also { delegate = it }
        speaker.speak(text)
    }

    override fun finish(onFinished: () -> Unit) {
        val speaker = delegate
        if (speaker == null) onFinished() else speaker.finish(onFinished)
    }

    override fun shutdown() {
        delegate?.shutdown()
        delegate = null
    }
}
