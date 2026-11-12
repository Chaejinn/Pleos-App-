package com.pleos.explorer.platform.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/** ON_DEVICE 전용. 앱 등록용 자격 증명이나 서버 연결을 요구하지 않는다. */
class PleosSpeaker(context: Context, private val onStatus: (String) -> Unit) : Speaker {
    private val handler = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val queue = ArrayDeque<String>()
    private var ready = false
    private var speaking = false
    private var failed = false
    private var closed = false
    private var finishCallback: (() -> Unit)? = null
    private val timeout = Runnable { complete() }
    private fun dispatch(block: () -> Unit) { handler.post { if (!closed) block() } }

    private val listener = object : EventListener {
        override fun onReady() = dispatch { ready = true; pump() }
        override fun onStart() = dispatch { onStatus("재생 중") }
        override fun onDone() = dispatch {
            speaking = false
            onStatus("재생 완료")
            pump()
        }
        override fun onStop() = dispatch { speaking = false; pump() }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) = dispatch { fail(errorType.toString()) }
    }

    init {
        try {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() = dispatch {
                    try {
                        tts.addEventListener(listener)
                        // Pleos 엔진은 언어와 지역이 모두 있는 ko-KR을 요구한다.
                        tts.setLocale(Locale.KOREA)
                        ready = true
                        onStatus("ON_DEVICE 준비됨")
                        pump()
                    } catch (e: Exception) { fail(e.message ?: "초기화 실패") }
                }
                override fun onFailure(errorType: ErrorType) = dispatch { fail(errorType.toString()) }
            })
        } catch (e: Exception) { dispatch { fail(e.message ?: "연결 실패") } }
    }
    private fun fail(message: String) {
        failed = true
        speaking = false
        queue.clear()
        onStatus("오류: $message")
        complete()
    }
    override fun speak(text: String) {
        if (closed || failed) return
        queue.addLast(text)
        pump()
    }
    private fun pump() {
        if (!ready || speaking || closed || failed) return
        if (queue.isEmpty()) { complete(); return }
        speaking = true
        try { tts.speak(queue.removeFirst()) }
        catch (e: Exception) { fail(e.message ?: "발화 실패") }
    }
    override fun finish(onFinished: () -> Unit) {
        finishCallback = onFinished
        handler.postDelayed(timeout, 20_000)
        if (failed || (ready && !speaking && queue.isEmpty())) complete()
    }
    private fun complete() {
        val callback = finishCallback ?: return
        finishCallback = null
        handler.removeCallbacks(timeout)
        callback()
    }
    override fun shutdown() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        finishCallback = null
        queue.clear()
        runCatching { tts.removeEventListener(listener) }
        runCatching { tts.stop() }
        runCatching { tts.release() }
    }
}
