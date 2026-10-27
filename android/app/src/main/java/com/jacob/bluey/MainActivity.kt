package com.jacob.bluey

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.jacob.bluey.face.FaceAnimator
import com.jacob.bluey.face.drawFace
import com.jacob.bluey.link.LaptopLink
import com.jacob.bluey.link.Packet
import com.jacob.bluey.link.PairingRecord

/**
 * Bluey's face, full screen and sideways under the laptop. Over USB (adb reverse) it connects
 * to the laptop app at localhost, and the laptop's face packets make the eyes follow its mouse.
 */
class MainActivity : ComponentActivity(), LaptopLink.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val animator = FaceAnimator()
    private lateinit var store: PairingStore
    private lateinit var link: LaptopLink
    private var status by mutableStateOf("")
    private var paired by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = PairingStore(this)
        val deviceName = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ")
        link = LaptopLink(post = { main.post(it) }, listener = this, deviceName = deviceName)
        val fredoka = try {
            resources.getFont(R.font.fredoka)
        } catch (error: Exception) {
            null
        }

        setContent { Screen(fredoka) }
    }

    // Like the iPhone app: the link (and later, focus monitoring) only runs while Bluey is on screen.
    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        link.stop()
        super.onStop()
    }

    private fun connect() {
        val record = store.load()
        paired = record != null
        if (record == null) {
            status = "Not paired yet. Tap Pair and scan the code in the laptop app."
        } else {
            link.start(record)
        }
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val record = PairingRecord.parse(barcode.rawValue)
                if (record == null) {
                    status = "That isn't a Bluey pairing code. Scan the QR code in the laptop app."
                } else {
                    store.save(record)
                    status = "Paired with ${record.service}."
                    connect()
                }
            }
            .addOnCanceledListener { if (!paired) status = "Not paired yet. Tap Pair and scan the code in the laptop app." }
            .addOnFailureListener { error -> status = "Couldn't open the scanner: ${error.message}" }
    }

    override fun onStatus(status: LaptopLink.Status) {
        this.status = when (status) {
            LaptopLink.Status.Connecting -> "Looking for the laptop over USB…"
            is LaptopLink.Status.Connected -> "Connected to ${status.laptop ?: "the laptop"}"
            is LaptopLink.Status.Waiting -> status.reason
        }
    }

    override fun onPacket(packet: Packet) {
        packet.face?.let { animator.receive(it, now()) }
    }

    private fun now() = System.nanoTime() / 1e9

    @Composable
    private fun Screen(fredoka: Typeface?) {
        // Redraw every frame; the animator works out the motion from the clock.
        var frame by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { frame = it }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) {
                // Reading the frame clock here is what makes Compose redraw on every frame.
                @Suppress("UNUSED_VARIABLE") val tick = frame
                drawFace(animator.step(now()), fredoka)
            }
            val dim = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            BasicText(status, Modifier.align(Alignment.TopStart).padding(12.dp), style = dim)
            BasicText(
                if (paired) "Re-pair" else "Pair",
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.White.copy(alpha = if (paired) 0.08f else 0.2f), RoundedCornerShape(50))
                    .clickable { scan() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = dim.copy(color = Color.White.copy(alpha = if (paired) 0.55f else 0.95f), fontSize = 14.sp),
            )
        }
    }
}
