package com.fototrappola.notifier

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mostra sopra lockscreen e accendi schermo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val initialId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NotificationScreen(
                    initialMessageId = initialId,
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ricompone con nuovo extra
        recreate()
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NotificationScreen(
    initialMessageId: Long,
    onDismiss: () -> Unit,
) {
    val messages by EmailRepository.messages.collectAsState()

    LaunchedEffect(messages.isEmpty()) {
        if (messages.isEmpty()) onDismiss()
    }

    if (messages.isEmpty()) return

    val startIndex = remember(initialMessageId, messages) {
        val idx = messages.indexOfFirst { it.id == initialMessageId }
        if (idx >= 0) idx else 0
    }
    val pagerState = rememberPagerState(initialPage = startIndex) { messages.size }
    var fullScreenBitmap by remember { mutableStateOf<Bitmap?>(null) }

    BackHandler { onDismiss() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        "Fototrappola (${pagerState.currentPage + 1}/${messages.size})",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                val msg = messages.getOrNull(page) ?: return@HorizontalPager
                MessagePage(
                    message = msg,
                    onThumbnailTap = { bitmap -> fullScreenBitmap = bitmap },
                    onDismiss = {
                        EmailRepository.remove(msg.id)
                    }
                )
            }
        }
    }

    fullScreenBitmap?.let { bmp ->
        Dialog(
            onDismissRequest = { fullScreenBitmap = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullScreenBitmap = null },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun MessagePage(
    message: EmailMessage,
    onThumbnailTap: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message.subject,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateFormatter.format(Date(message.receivedAt)),
                fontSize = 14.sp,
                color = Color(0xFFB0B0B0)
            )
            Text(
                text = "${message.attachments.size} img",
                fontSize = 14.sp,
                color = Color(0xFFB0B0B0)
            )
        }

        Text(
            text = message.from,
            fontSize = 12.sp,
            color = Color(0xFF808080),
            maxLines = 1,
        )

        if (message.attachments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Nessun allegato immagine", color = Color.Gray)
            }
        } else {
            // Griglia adattiva: se <=3 colonna larga, se >3 LazyRow scrollabile
            if (message.attachments.size <= 2) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    message.attachments.forEach { att ->
                        ThumbnailView(att, Modifier.fillMaxWidth().weight(1f), onThumbnailTap)
                    }
                }
            } else {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(message.attachments) { att ->
                        ThumbnailView(
                            att,
                            Modifier.fillMaxHeight().aspectRatio(0.75f),
                            onThumbnailTap
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Vista")
            }
        }
    }
}

@Composable
fun ThumbnailView(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    onTap: (Bitmap) -> Unit,
) {
    val bmp = attachment.bitmap
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222))
            .clickable { bmp?.let(onTap) },
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = attachment.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(attachment.filename, color = Color.Gray, fontSize = 10.sp)
        }
    }
}
