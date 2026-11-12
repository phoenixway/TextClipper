package ua.textclipper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Restored Navigation and disk drafts already contain a previously delivered intent.
        if (savedInstanceState == null) receive(intent)
        setContent { TextClipper(model) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) {
        val payload = capturePayload(intent) ?: return
        runCatching { model.acceptShare(payload.text, payload.title) }
            .onFailure { model.message = "Не вдалося прийняти текст: ${it.message}" }
    }
}

data class CapturePayload(val text: String, val title: String)
fun capturePayload(intent: Intent?): CapturePayload? {
    if (intent?.type != "text/plain") return null
    val text = when (intent.action) {
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        else -> null
    } ?: return null
    return CapturePayload(text.toString(), intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty())
}
