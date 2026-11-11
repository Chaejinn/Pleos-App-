package com.pleos.explorer.platform.speech

interface Speaker {
    fun speak(text: String)
    fun finish(onFinished: () -> Unit)
    fun shutdown()
}
