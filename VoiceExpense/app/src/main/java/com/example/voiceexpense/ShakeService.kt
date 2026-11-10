package com.example.voiceexpense

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "shake_listener"
        private const val SHAKE_G = 2.7f        // lower = more sensitive
        private const val SHAKES_NEEDED = 3     // jerks within 1 second
    }

    private lateinit var store: ExpenseStore
    private lateinit var sm: SensorManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var lastEvent = 0L
    private var windowStart = 0L
    private var hits = 0
    private var lastTrigger = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        store = ExpenseStore(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shake listener", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Voice Expense is on")
            .setContentText("Shake your phone and say: spent 200 on lunch")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        sm.unregisterListener(this)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- shake detection ----------

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
            SensorManager.GRAVITY_EARTH
        if (g < SHAKE_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEvent < 120) return
        lastEvent = now
        if (now - windowStart > 1000) { windowStart = now; hits = 0 }
        hits++
        if (hits >= SHAKES_NEEDED && !busy && now - lastTrigger > 4000) {
            lastTrigger = now
            hits = 0
            startListening()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- voice ----------

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    private fun buzz() {
        @Suppress("DEPRECATION")
        (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            .vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun finish() {
        busy = false
        main.post { recognizer?.destroy(); recognizer = null }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) {
            speak("Microphone or speech recognition is not available")
            return
        }
        busy = true
        buzz() // buzz = "I'm listening now"
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val parsed = text?.let { ExpenseParser.parse(it) }
                    if (parsed == null) {
                        speak("Sorry, I did not catch an amount. Say, spent 200 on lunch")
                    } else {
                        val e = Expense(System.currentTimeMillis(), parsed.amount, parsed.category, parsed.note)
                        Thread { store.add(e) }.start()
                        val amt = if (parsed.amount % 1.0 == 0.0) parsed.amount.toLong().toString() else parsed.amount.toString()
                        speak("Added $amt rupees under ${parsed.category}")
                    }
                    finish()
                }

                override fun onError(error: Int) {
                    speak("I did not hear anything")
                    finish()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        recognizer?.startListening(i)
    }
}
