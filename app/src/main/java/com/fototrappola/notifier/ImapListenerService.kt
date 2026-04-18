package com.fototrappola.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Properties

class ImapListenerService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionJob: Job? = null
    private var currentStore: Store? = null
    private var currentFolder: IMAPFolder? = null

    companion object {
        private const val TAG = "ImapListener"
        private const val CHANNEL_ID_FOREGROUND = "fototrappola_foreground"
        private const val CHANNEL_ID_ALERT = "fototrappola_alert"
        private const val FOREGROUND_NOTIF_ID = 1
        private const val ALERT_NOTIF_ID_BASE = 1000
        private const val IDLE_RESTART_INTERVAL_MS = 25L * 60 * 1000 // 25 min

        fun start(context: Context) {
            val intent = Intent(context, ImapListenerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImapListenerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fototrappola::ImapWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification("In ascolto..."))

        if (!CredentialStore.isConfigured(this)) {
            Log.w(TAG, "Credenziali non configurate, stop service")
            stopSelf()
            return START_NOT_STICKY
        }

        if (connectionJob?.isActive != true) {
            wakeLock?.acquire()
            connectionJob = lifecycleScope.launch(Dispatchers.IO) {
                connectionLoop()
            }
        }
        return START_STICKY
    }

    private suspend fun connectionLoop() {
        val email = CredentialStore.email(this) ?: return
        val password = CredentialStore.password(this) ?: return
        var backoffSec = 5L

        while (currentCoroutineContext().isActive) {
            try {
                updateForegroundText("Connessione a Gmail...")
                connectAndIdle(email, password)
                backoffSec = 5L
            } catch (e: Exception) {
                Log.e(TAG, "Errore connessione IMAP", e)
                updateForegroundText("Errore: ${e.message?.take(40)}. Retry tra ${backoffSec}s")
                closeQuietly()
                delay(backoffSec * 1000)
                backoffSec = (backoffSec * 2).coerceAtMost(300L)
            }
        }
    }

    private suspend fun connectAndIdle(email: String, password: String) {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.trust", "imap.gmail.com")
            put("mail.imaps.connectiontimeout", "30000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.usesocketchannels", "true")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect("imap.gmail.com", email, password)
        currentStore = store

        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        currentFolder = folder

        updateForegroundText("In ascolto su $email")

        // Processa email non lette già presenti
        processExistingUnread(folder)

        folder.addMessageCountListener(object : MessageCountAdapter() {
            override fun messagesAdded(event: MessageCountEvent) {
                for (msg in event.messages) {
                    try {
                        handleNewMessage(folder, msg)
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore handling message", e)
                    }
                }
            }
        })

        // Loop IDLE con restart periodico per evitare timeout del server
        val startTime = System.currentTimeMillis()
        while (folder.isOpen && currentCoroutineContext().isActive) {
            if (System.currentTimeMillis() - startTime > IDLE_RESTART_INTERVAL_MS) {
                Log.d(TAG, "Restart IDLE pianificato")
                break
            }
            try {
                folder.idle()
            } catch (e: Exception) {
                Log.w(TAG, "IDLE interrotto: ${e.message}")
                break
            }
        }

        closeQuietly()
    }

    private fun processExistingUnread(folder: IMAPFolder) {
        try {
            val unreadFlagTerm = jakarta.mail.search.FlagTerm(
                jakarta.mail.Flags(jakarta.mail.Flags.Flag.SEEN), false
            )
            val msgs = folder.search(unreadFlagTerm)
            Log.d(TAG, "Email non lette già presenti: ${msgs.size}")
            for (msg in msgs) {
                handleNewMessage(folder, msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore scan unread", e)
        }
    }

    private fun handleNewMessage(folder: IMAPFolder, msg: Message) {
        val filter = CredentialStore.filterSender(this)
        val fromStr = msg.from?.joinToString { it.toString() } ?: ""

        if (!filter.isNullOrBlank() && !fromStr.contains(filter, ignoreCase = true)) {
            Log.d(TAG, "Messaggio filtrato (from=$fromStr)")
            return
        }

        val uid = folder.getUID(msg)
        if (EmailRepository.messages.value.any { it.uid == uid }) return

        val subject = msg.subject ?: "(senza oggetto)"
        val receivedAt = msg.receivedDate?.time ?: System.currentTimeMillis()
        val attachments = extractImageAttachments(msg)

        Log.d(TAG, "Nuova email: $subject, allegati immagine: ${attachments.size}")

        val stored = EmailRepository.add(uid, subject, fromStr, receivedAt, attachments)
        showFullScreenNotification(stored)
    }

    private fun extractImageAttachments(msg: Message): List<Attachment> {
        val result = mutableListOf<Attachment>()
        try {
            val content = msg.content
            if (content is Multipart) {
                walkMultipart(content, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore estrazione allegati", e)
        }
        return result
    }

    private fun walkMultipart(mp: Multipart, out: MutableList<Attachment>) {
        for (i in 0 until mp.count) {
            val part = mp.getBodyPart(i)
            val inner = runCatching { part.content }.getOrNull()
            if (inner is Multipart) {
                walkMultipart(inner, out)
                continue
            }
            val disposition = part.disposition
            val contentType = part.contentType?.lowercase() ?: ""
            val isImage = contentType.startsWith("image/") ||
                    (Part.ATTACHMENT.equals(disposition, ignoreCase = true) &&
                            (part.fileName?.lowercase()?.matches(Regex(".*\\.(jpg|jpeg|png|webp|heic|gif)$")) == true))
            if (isImage) {
                try {
                    val baos = ByteArrayOutputStream()
                    part.inputStream.use { it.copyTo(baos) }
                    out.add(Attachment(part.fileName ?: "image_${out.size}", baos.toByteArray()))
                } catch (e: Exception) {
                    Log.e(TAG, "Errore lettura allegato", e)
                }
            }
        }
    }

    private fun showFullScreenNotification(msg: EmailMessage) {
        val fullScreenIntent = Intent(this, NotificationActivity::class.java).apply {
            putExtra(NotificationActivity.EXTRA_MESSAGE_ID, msg.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, msg.id.toInt(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(msg.subject)
            .setContentText("${msg.attachments.size} immagini dalla fototrappola")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIF_ID_BASE + msg.id.toInt(), notif)
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val foreground = NotificationChannel(
            CHANNEL_ID_FOREGROUND, "Servizio Fototrappola",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Servizio in ascolto email" }
        nm.createNotificationChannel(foreground)

        val alert = NotificationChannel(
            CHANNEL_ID_ALERT, "Notifiche Fototrappola",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Nuove email dalla fototrappola"
            enableVibration(true)
            enableLights(true)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(alert)
    }

    private fun buildForegroundNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Fototrappola attiva")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundText(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_NOTIF_ID, buildForegroundNotification(text))
    }

    private fun closeQuietly() {
        runCatching { currentFolder?.close(false) }
        runCatching { currentStore?.close() }
        currentFolder = null
        currentStore = null
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        closeQuietly()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
