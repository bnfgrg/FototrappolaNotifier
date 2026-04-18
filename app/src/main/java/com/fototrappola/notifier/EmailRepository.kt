package com.fototrappola.notifier

import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class Attachment(
    val filename: String,
    val bytes: ByteArray,
) {
    val bitmap by lazy {
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}

data class EmailMessage(
    val id: Long,
    val uid: Long,           // IMAP UID
    val subject: String,
    val from: String,
    val receivedAt: Long,    // epoch ms
    val attachments: List<Attachment>,
)

object EmailRepository {
    private val idGen = AtomicLong(0L)

    private val _messages = MutableStateFlow<List<EmailMessage>>(emptyList())
    val messages: StateFlow<List<EmailMessage>> = _messages

    fun add(uid: Long, subject: String, from: String, receivedAt: Long, attachments: List<Attachment>): EmailMessage {
        val msg = EmailMessage(
            id = idGen.incrementAndGet(),
            uid = uid,
            subject = subject,
            from = from,
            receivedAt = receivedAt,
            attachments = attachments,
        )
        _messages.update { current ->
            // evita duplicati per UID
            if (current.any { it.uid == uid }) current
            else current + msg
        }
        return msg
    }

    fun remove(id: Long) {
        _messages.update { it.filterNot { m -> m.id == id } }
    }

    fun clear() {
        _messages.update { emptyList() }
    }

    fun getById(id: Long): EmailMessage? = _messages.value.firstOrNull { it.id == id }
}
