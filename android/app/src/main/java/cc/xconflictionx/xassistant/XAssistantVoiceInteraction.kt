package cc.xconflictionx.xassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class XAssistantVoiceInteractionService : VoiceInteractionService()

class XAssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        XAssistantVoiceInteractionSession(this)
}

private class XAssistantVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context) {
    private val appContext = context.applicationContext
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(appContext, MainActivity::class.java)
                .putExtra("cc.xconflictionx.xassistant.EXTRA_ASSISTANT_LAUNCH", true)
        )
        hide()
    }
}
