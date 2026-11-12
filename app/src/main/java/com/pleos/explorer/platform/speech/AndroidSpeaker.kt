package com.pleos.explorer.platform.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 초기화와 발화 완료까지 기다린 뒤 서비스를 종료할 수 있는 Android 기본 TTS. */
class AndroidSpeaker(context: Context) : Speaker {
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var closed = false
    private var sequence = 0L
    private val pending = mutableListOf<String>()
    private val utterances = mutableSetOf<String>()
    private var onFinished: (() -> Unit)? = null
    private val timeout = Runnable { complete() }

    private val tts = TextToSpeech(context.applicationContext) { status -> handler.post {
        if (closed) return@post
        failed = status != TextToSpeech.SUCCESS
        if (!failed) {
            val language = setKorean()
            failed = language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED
        }
        ready = !failed
        val queued = pending.toList()
        pending.clear()
        if (ready) queued.forEach(::say)
        checkFinished()
    } }

    private fun setKorean(): Int = tts.setLanguage(Locale.KOREAN)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = done(utteranceId)
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) = done(utteranceId)
        })
    }
    private fun done(id: String?) { handler.post { utterances.remove(id); checkFinished() } }
    private fun say(text: String) {
        val id = (++sequence).toString()
        utterances += id
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) utterances -= id
    }
    override fun speak(text: String) {
        if (closed || failed) return
        if (ready) say(text) else pending += text
    }
    override fun finish(onFinished: () -> Unit) {
        this.onFinished = onFinished
        handler.postDelayed(timeout, 20_000)
        checkFinished()
    }
    private fun checkFinished() {
        if ((ready || failed) && pending.isEmpty() && utterances.isEmpty()) complete()
    }
    private fun complete() {
        val callback = onFinished ?: return
        onFinished = null
        handler.removeCallbacks(timeout)
        callback()
    }
    override fun shutdown() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        pending.clear(); utterances.clear(); onFinished = null
        tts.stop()
        tts.shutdown()
    }
}
