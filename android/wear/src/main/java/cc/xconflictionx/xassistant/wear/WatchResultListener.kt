package cc.xconflictionx.xassistant.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchResultListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/xassistant/result") return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra("xassistant_result", event.data.toString(Charsets.UTF_8))
        runCatching { startActivity(launch) }.onFailure { Log.w("xAssistantWatch", "Unable to show result", it) }
    }
}