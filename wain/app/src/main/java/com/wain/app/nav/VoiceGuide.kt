package com.wain.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Android text-to-speech wrapper. If the device has no voice for the app language, [languageAvailable] is false. */
class VoiceGuide(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingLang: String? = null

    @Volatile
    var languageAvailable: Boolean = true
        private set

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        pendingLang?.let { setLanguage(it) }
    }

    fun setLanguage(lang: String) {
        pendingLang = lang
        val engine = tts ?: return
        if (!ready) return
        val result = engine.setLanguage(Locale.forLanguageTag(lang))
        languageAvailable = result >= TextToSpeech.LANG_AVAILABLE
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready || !languageAvailable) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wain-guidance")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
