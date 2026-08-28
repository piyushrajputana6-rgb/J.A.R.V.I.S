package com.jassistant.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

/**
 * Runs continuously in the foreground (persistent notification, required by Android so the
 * user always knows the mic is being used) and repeatedly listens for the wake phrase.
 *
 * Honest limitation: this uses Android's built-in SpeechRecognizer, which is designed for
 * short one-shot listens, not true 24/7 always-on detection. It restarts itself after every
 * result/error/timeout, which works but is less battery-efficient and less instant than a
 * dedicated offline wake-word engine (e.g. Picovoice Porcupine). Good starting point; that's
 * the natural upgrade path if reliability becomes an issue.
 */
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val wakePhrase = "hey j"
    private val channelId = "j_wake_channel"
    private val notificationId = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Listening for \"Hey J.\"")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(notificationId, notification)
        }
        running = true
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun startListeningLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition isn't available on this device")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                checkForWakeWord(results)
                restart()
            }
            override fun onPartialResults(partialResults: Bundle) {
                checkForWakeWord(partialResults)
            }
            override fun onError(error: Int) { restart() }
            override fun onEndOfSpeech() {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listenOnce()
    }

    private fun listenOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restart()
        }
    }

    private fun restart() {
        if (!running) return
        handler.postDelayed({ if (running) listenOnce() }, 400)
    }

    private fun checkForWakeWord(bundle: Bundle) {
        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (m in matches) {
            if (m.lowercase().contains(wakePhrase)) {
                onWakeWordDetected()
                return
            }
        }
    }

    private fun onWakeWordDetected() {
        recognizer?.stopListening()
        updateNotification("Heard you — opening J.…")
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("autolisten", true)
        }
        startActivity(launch)
        handler.postDelayed({
            updateNotification("Listening for \"Hey J.\"")
            restart()
        }, 2500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "J. background listener", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("J. is running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(notificationId, buildNotification(text))
    }
}
