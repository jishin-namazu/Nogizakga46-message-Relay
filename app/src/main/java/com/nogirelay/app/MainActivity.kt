package com.nogirelay.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nogirelay.app.call.FullScreenPermission
import com.nogirelay.app.call.IncomingCallActivity
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.call.OverlayPermission
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.AppSettings
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.api.ApiConfig
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.notification.NotificationChannels
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.translation.normalizeTranslationText
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.MediaViewerActivity
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SignalCoral
import com.nogirelay.app.ui.SignalGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val syncRequests = MutableStateFlow(0L)
    private lateinit var proximityControl: com.nogirelay.app.call.ProximityScreenControl
    private lateinit var audioManager: android.media.AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch theme mirrors the official app splash until Compose draws its first frame.
        setTheme(R.style.Theme_NogiRelay)
        AppGraph.initialize(this)
        NotificationChannels.create(this)
        AppGraph.database.deleteTestMessages().forEach { IncomingCallNotifier.cancel(this, it) }
        if (AppGraph.settings.read().relayUrl.isNotBlank()) {
            PushRegistrar.registerCurrentToken(this)
        }
        
        proximityControl = com.nogirelay.app.call.OfficialProximityScreenControl(this)
        audioManager = getSystemService(android.media.AudioManager::class.java)

        setContent {
            NogiRelayTheme {
                RelayApp(
                    initialMessageId = intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID),
                    onOpenMedia = ::openMedia,
                    onPlayVoice = ::playVoice,
                    onTestCall = ::testCall,
                    syncRequests = syncRequests,
                    onManualSync = { syncRequests.update { it + 1 } },
                    onUpdateProximity = ::updateProximityLock,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        syncRequests.update { it + 1 }
    }

    override fun onDestroy() {
        proximityControl.close()
        super.onDestroy()
    }

    private fun updateProximityLock(playback: VoicePlaybackState) {
        val speakerOn = playback.speakerOn
        val isExternalAudioConnected = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .any { device ->
                device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }
        val shouldEnable = playback.isPlaying && !speakerOn && !isExternalAudioConnected
        proximityControl.setEnabled(shouldEnable)
    }

    private fun openMedia(message: RelayMessage) {
        startActivity(Intent(this, MediaViewerActivity::class.java).putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, message.id))
    }

    private fun playVoice(message: RelayMessage) {
        startService(
            Intent(this, VoicePlaybackService::class.java).apply {
                action = VoicePlaybackService.ACTION_PLAY
                putExtra(VoicePlaybackService.EXTRA_MESSAGE_ID, message.id)
            },
        )
    }

    private fun testCall() {
        val id = "test-call-${System.currentTimeMillis()}"
        val message = RelayMessage(
            id = id,
            memberId = "test",
            memberName = "池田 瑛紗",
            memberAvatarUrl = null,
            phoneImageUrl = Uri.parse("android.resource://$packageName/${R.drawable.ikeda_teresa_phone_image}").toString(),
            type = MessageType.AUDIO,
            text = "全屏来电测试",
            mediaUrl = Uri.parse("android.resource://$packageName/${R.raw.test_voice}").toString(),
            thumbnailUrl = null,
            durationSeconds = null,
            sentAt = Instant.now().toString(),
            incomingCallFrom = "池田 瑛紗",
            ringtoneUrl = null,
            isPlayed = false,
        )
        AppGraph.database.insert(message)
        startActivity(
            Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, message.id)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }
}

private enum class AppTab(val label: String) { HOME("概览"), MESSAGES("消息"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayApp(
    initialMessageId: String?,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onTestCall: () -> Unit,
    syncRequests: StateFlow<Long>,
    onManualSync: () -> Unit,
    onUpdateProximity: (VoicePlaybackState) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableStateOf(if (initialMessageId == null) AppTab.HOME else AppTab.MESSAGES) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var fullScreenGranted by remember { mutableStateOf(FullScreenPermission.canUse(context)) }
    var overlayGranted by remember { mutableStateOf(OverlayPermission.canUse(context)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var syncLabel by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            refreshKey++
            fullScreenGranted = FullScreenPermission.canUse(context)
            overlayGranted = OverlayPermission.canUse(context)
            notificationGranted = hasNotificationPermission(context)
        }
    }

    LaunchedEffect(syncRequests) {
        syncRequests.collectLatest {
            syncing = true
            val result = runCatching { withContext(Dispatchers.IO) { syncMessagesFromServer(context) } }
            syncing = false
            syncLabel = result.fold(
                onSuccess = { count -> if (count > 0) "已同步 $count 条历史消息" else "历史消息已是最新" },
                onFailure = { error ->
                    Log.w("NogiRelay", "History sync failed", error)
                    error.message ?: "历史消息同步失败"
                },
            )
            refreshKey++
        }
    }

    LaunchedEffect(refreshKey) {
        TranslationManager.enqueue(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (tab == AppTab.HOME) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Nogi Relay", fontWeight = FontWeight.SemiBold)
                            Text(tab.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    AppTab.HOME -> Icons.Rounded.Home
                                    AppTab.MESSAGES -> Icons.Rounded.Inbox
                                    AppTab.SETTINGS -> Icons.Rounded.Settings
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.HOME -> HomeScreen(
                    notificationGranted = notificationGranted,
                    fullScreenGranted = fullScreenGranted,
                    overlayGranted = overlayGranted,
                    refreshKey = refreshKey,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenFullScreenSettings = {
                        FullScreenPermission.settingsIntent(context)?.let(context::startActivity)
                    },
                    onOpenOverlaySettings = {
                        context.startActivity(OverlayPermission.settingsIntent(context))
                    },
                    onTestCall = onTestCall,
                    onOpenSettings = { tab = AppTab.SETTINGS },
                    isSyncing = syncing,
                    syncLabel = syncLabel,
                    onSyncHistory = onManualSync,
                )

                AppTab.MESSAGES -> MessagesScreen(
                    refreshKey = refreshKey,
                    initialMessageId = initialMessageId,
                    onOpenMedia = onOpenMedia,
                    onPlayVoice = onPlayVoice,
                    onUpdateProximity = onUpdateProximity,
                )

                AppTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

private fun syncMessagesFromServer(context: Context): Int {
    val savedSettings = AppGraph.settings.read()
    val settings = savedSettings.copy(
        relayUrl = savedSettings.relayUrl.ifBlank { ApiConfig.BASE_URL },
        accessToken = savedSettings.accessToken.ifBlank { ApiConfig.ACCESS_TOKEN },
    )
    if (settings.relayUrl.isBlank() || settings.accessToken.isBlank()) return 0

    var offset = 0
    var inserted = 0
    var pageCount = 0
    while (pageCount++ < 50) {
        val page = AppGraph.relayClient.fetchMessages(settings, limit = 200, offset = offset)
        if (page.isEmpty()) break
        page.forEach { message ->
            if (AppGraph.database.insert(message)) inserted++
            if (message.type != MessageType.TEXT) {
                runCatching { MediaDownloader.enqueueIfNeeded(context, message) }
                    .onFailure { error -> Log.w("NogiRelay", "Media download enqueue failed for ${message.id}", error) }
            }
        }
        offset += page.size
        if (page.size < 200) break
    }
    TranslationManager.enqueue(context)
    return inserted
}

private val messageDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatMessageDateTime(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return input

    runCatching { Instant.parse(input) }.getOrNull()?.let {
        return messageDateFormatter.withZone(ZoneId.systemDefault()).format(it)
    }
    runCatching { OffsetDateTime.parse(input).toInstant() }.getOrNull()?.let {
        return messageDateFormatter.withZone(ZoneId.systemDefault()).format(it)
    }

    val local = runCatching {
        LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(input.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull()
    return local?.format(messageDateFormatter) ?: input
}

@Composable
private fun HomeScreen(
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    refreshKey: Int,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestCall: () -> Unit,
    onOpenSettings: () -> Unit,
    isSyncing: Boolean,
    syncLabel: String,
    onSyncHistory: () -> Unit,
) {
    val settings = remember(refreshKey) { AppGraph.settings.read() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val firebaseConfigured = remember(refreshKey) { PushRegistrar.isConfigured(context) }
    val tokenRegistered = remember(refreshKey) { AppGraph.settings.pushToken().isNotBlank() }
    val pushReady = firebaseConfigured && tokenRegistered && settings.relayUrl.isNotBlank() && settings.accessToken.isNotBlank()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp),
    ) {
        item {
            StatusBand(
                pushReady = pushReady,
            )
        }
        item {
            Text("系统能力", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                title = "通知权限",
                description = if (notificationGranted) "系统通知已启用" else "需要授权后才能接收新消息",
                granted = notificationGranted,
                action = onRequestNotifications,
            )
            HorizontalDivider()
            PermissionRow(
                title = "全屏来电",
                description = if (fullScreenGranted) "允许在锁屏上显示成员来电" else "Android 14 需要开启特殊权限",
                granted = fullScreenGranted,
                action = onOpenFullScreenSettings,
            )
            HorizontalDivider()
            PermissionRow(
                title = "后台弹出界面",
                description = if (overlayGranted) "允许应用在后台直接弹出全屏来电" else "部分设备需要此权限才能弹出后台来电",
                granted = overlayGranted,
                action = onOpenOverlaySettings,
            )
            HorizontalDivider()
            PermissionRow(
                title = "FCM 系统推送",
                description = when {
                    !firebaseConfigured -> "缺少 Firebase google-services.json"
                    !tokenRegistered -> "设备尚未向服务器注册"
                    else -> "服务器可直接唤醒系统通知服务"
                },
                granted = firebaseConfigured && tokenRegistered,
                action = onOpenSettings,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSyncHistory, enabled = !isSyncing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (isSyncing) "正在同步历史消息..." else "主动同步历史消息", maxLines = 1)
                }
                Button(onClick = onTestCall, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Call, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("测试全屏来电", maxLines = 1)
                }
                FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("推送设置", maxLines = 1)
                }
                if (syncLabel.isNotBlank()) {
                    Text(syncLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatusBand(pushReady: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (pushReady) Color(0xFFE7F6EF) else Color(0xFFF2EDF3),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (pushReady) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (pushReady) SignalGreen else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = if (pushReady) "FCM 系统推送已就绪" else "FCM 推送尚未完成配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    action: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Notifications,
            contentDescription = null,
            tint = if (granted) SignalGreen else SignalCoral,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        if (!granted) FilledTonalButton(onClick = action) { Text("开启") }
    }
}

@Composable
private fun MessagesScreen(
    refreshKey: Int,
    initialMessageId: String?,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onUpdateProximity: (VoicePlaybackState) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadScope = rememberCoroutineScope()
    val retranslateScope = rememberCoroutineScope()
    val messages = remember(refreshKey) { AppGraph.database.latest() }
    val translationEnabled = remember(refreshKey) { AppGraph.settings.read().translationEnabled }
    val userNickname = remember(refreshKey) { AppGraph.settings.read().userNickname }
    val playbackState by VoicePlaybackService.playbackState.collectAsState()
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageInput by remember { mutableStateOf("1") }
    var pendingDownload by remember { mutableStateOf<RelayMessage?>(null) }
    
    LaunchedEffect(playbackState) {
        onUpdateProximity(playbackState)
    }
    
    LaunchedEffect(initialMessageId) {
        if (initialMessageId != null) {
            val message = AppGraph.database.find(initialMessageId)
            if (message != null) {
                selectedMemberId = message.memberId.ifBlank { message.memberName }
            }
        }
    }

    val saveDownload: (RelayMessage) -> Unit = { message ->
        downloadScope.launch(Dispatchers.IO) {
            val result = runCatching { MediaDownloader.saveToDownloads(context, message) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { "已保存到 Download/${it.displayName}" },
                        onFailure = { it.message ?: "无法保存媒体" },
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val message = pendingDownload
        pendingDownload = null
        if (granted && message != null) {
            saveDownload(message)
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存到 Download 文件夹", Toast.LENGTH_SHORT).show()
        }
    }
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Inbox, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("还没有同步消息", style = MaterialTheme.typography.titleMedium)
                Text("保存同步设置后，新消息会出现在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val threads = messages
        .groupBy { it.memberId.ifBlank { it.memberName } }
        .map { (memberId, memberMessages) ->
            MemberThread(
                id = memberId,
                name = memberMessages.first().memberName,
                avatarUrl = memberMessages.firstNotNullOfOrNull { it.memberAvatarUrl },
                latest = memberMessages.first(),
                count = memberMessages.size,
            )
        }
        .sortedByDescending { it.latest.sentAt }

    fun download(message: RelayMessage) {
        if (MediaDownloader.needsLegacyWritePermission(context)) {
            pendingDownload = message
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveDownload(message)
        }
    }

    val selected = selectedMemberId
    BackHandler(enabled = selected != null) {
        selectedMemberId = null
    }
    Crossfade(
        targetState = selected,
        animationSpec = tween(durationMillis = 220),
        label = "member-message-transition",
    ) { selectedMember ->
        if (selectedMember == null) {
            MemberInbox(
                threads = threads,
                onSelect = {
                    searchQuery = ""
                    selectedMemberId = it.id
                    
                    // Check if there's a playing message for this member
                    val currentPlayingMessageId = playbackState.messageId
                    if (playbackState.isPlaying && currentPlayingMessageId != null) {
                        val playingMessage = AppGraph.database.find(currentPlayingMessageId)
                        val playingMemberId = playingMessage?.memberId?.ifBlank { playingMessage.memberName }
                        if (playingMemberId == it.id) {
                            // Calculate which page the playing message is on
                            val allMessages = AppGraph.database.messagesForMember(
                                memberKey = it.id,
                                searchQuery = "",
                                limit = Int.MAX_VALUE,
                                offset = 0,
                            )
                            val playingIndex = allMessages.indexOfFirst { msg -> msg.id == currentPlayingMessageId }
                            if (playingIndex >= 0) {
                                val targetPage = playingIndex / MEMBER_MESSAGES_PAGE_SIZE
                                currentPage = targetPage
                                pageInput = (targetPage + 1).toString()
                            } else {
                                currentPage = 0
                                pageInput = "1"
                            }
                        } else {
                            currentPage = 0
                            pageInput = "1"
                        }
                    } else {
                        currentPage = 0
                        pageInput = "1"
                    }
                },
            )
        } else {
            val thread = threads.firstOrNull { it.id == selectedMember }
            val matchingMessageCount = remember(refreshKey, selectedMember, searchQuery) {
                AppGraph.database.countMessagesForMember(selectedMember, searchQuery)
            }
            val totalPages = ((matchingMessageCount + MEMBER_MESSAGES_PAGE_SIZE - 1) / MEMBER_MESSAGES_PAGE_SIZE)
                .coerceAtLeast(1)
            val page = currentPage.coerceIn(0, totalPages - 1)
            val messageListState = rememberLazyListState()
            var showPageDialog by remember(selectedMember, searchQuery) { mutableStateOf(false) }
            val memberMessages = remember(refreshKey, selectedMember, searchQuery, page) {
                AppGraph.database.messagesForMember(
                    memberKey = selectedMember,
                    searchQuery = searchQuery,
                    limit = MEMBER_MESSAGES_PAGE_SIZE,
                    offset = page * MEMBER_MESSAGES_PAGE_SIZE,
                )
            }
            
            fun goToPage(targetPage: Int) {
                val safePage = targetPage.coerceIn(0, totalPages - 1)
                currentPage = safePage
                pageInput = (safePage + 1).toString()
            }
            val requestedPage = pageInput.toIntOrNull()
            val canJump = requestedPage != null && requestedPage in 1..totalPages
            LaunchedEffect(selectedMember, searchQuery, page) {
                if (currentPage != page) currentPage = page
                pageInput = (page + 1).toString()
                if (memberMessages.isNotEmpty()) {
                    // Check if playing message is in current page
                    if (playbackState.isPlaying && playbackState.messageId != null) {
                        val playingIndex = memberMessages.indexOfFirst { it.id == playbackState.messageId }
                        if (playingIndex >= 0) {
                            messageListState.scrollToItem(playingIndex + 1)
                        } else {
                            messageListState.scrollToItem(0)
                        }
                    } else {
                        messageListState.scrollToItem(0)
                    }
                }
            }
            if (showPageDialog) {
                AlertDialog(
                    onDismissRequest = { showPageDialog = false },
                    title = { Text("跳转") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "请输入1-${totalPages}之间的页码",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = pageInput,
                                onValueChange = { value ->
                                    pageInput = value.filter(Char::isDigit).take(6)
                                },
                                singleLine = true,
                                label = { Text("页码") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        if (canJump) {
                                            goToPage(requestedPage!! - 1)
                                            showPageDialog = false
                                        }
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                goToPage(requestedPage!! - 1)
                                showPageDialog = false
                            },
                            enabled = canJump,
                        ) { Text("跳转") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPageDialog = false }) { Text("取消") }
                    },
                )
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = { selectedMemberId = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回成员列表")
                    }
                    Text(
                        text = thread?.name ?: "成员消息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LazyColumn(
                    state = messageListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                currentPage = 0
                                pageInput = "1"
                            },
                            singleLine = true,
                            label = { Text("搜索") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            currentPage = 0
                                            pageInput = "1"
                                        },
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                    if (memberMessages.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) "暂无消息" else "没有找到相关消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            )
                        }
                    }
                    items(memberMessages, key = { it.id }) { message ->
                        MessageCard(
                            message = message,
                            audioState = playbackState.takeIf { it.messageId == message.id },
                            translationEnabled = translationEnabled,
                            userNickname = userNickname,
                            onOpenMedia = { onOpenMedia(message) },
                            onPlayVoice = { onPlayVoice(message) },
                            onDownload = { download(message) },
                            onRetranslate = {
                                retranslateScope.launch {
                                    AppGraph.database.markForRetranslation(message.id)
                                    TranslationManager.enqueue(context)
                                }
                            },
                        )
                    }
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = "$matchingMessageCount 条消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = { goToPage(page - 1) },
                                    enabled = page > 0,
                                ) { Text("上一页") }
                                OutlinedButton(
                                    onClick = {
                                        pageInput = (page + 1).toString()
                                        showPageDialog = true
                                    },
                                    enabled = matchingMessageCount > 0,
                                ) {
                                    Text("第 ${page + 1} / $totalPages 页")
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码")
                                }
                                OutlinedButton(
                                    onClick = { goToPage(page + 1) },
                                    enabled = page < totalPages - 1,
                                ) { Text("下一页") }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

private const val MEMBER_MESSAGES_PAGE_SIZE = 20

private data class MemberThread(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val latest: RelayMessage,
    val count: Int,
)

@Composable
private fun MemberInbox(
    threads: List<MemberThread>,
    onSelect: (MemberThread) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "最近收到",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                items(threads.take(6), key = { it.id }) { thread ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(92.dp).clickable { onSelect(thread) },
                    ) {
                        RemoteImage(
                            url = thread.avatarUrl,
                            contentDescription = thread.name,
                            modifier = Modifier.size(72.dp).clip(CircleShape),
                            loadCachedImmediately = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            thread.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "全部成员",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(threads, key = { it.id }) { thread ->
            Card(
                onClick = { onSelect(thread) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    RemoteImage(
                        url = thread.avatarUrl,
                        contentDescription = thread.name,
                        modifier = Modifier.size(50.dp).clip(CircleShape),
                        loadCachedImmediately = true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(thread.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = threadPreview(thread.latest),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        text = thread.count.toString(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private fun threadPreview(message: RelayMessage): String = when (message.type) {
    MessageType.TEXT -> message.text.orEmpty()
    MessageType.IMAGE -> "图片消息"
    MessageType.AUDIO -> "语音消息"
    MessageType.VIDEO -> "视频消息"
}.let { fallback -> message.text?.trim()?.takeIf { it.isNotEmpty() } ?: fallback }

@Composable
private fun MessageCard(
    message: RelayMessage,
    audioState: VoicePlaybackState?,
    translationEnabled: Boolean,
    userNickname: String,
    onOpenMedia: () -> Unit,
    onPlayVoice: () -> Unit,
    onDownload: () -> Unit,
    onRetranslate: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scrubPositionMs by remember(message.id) { mutableIntStateOf(0) }
    var scrubbing by remember(message.id) { mutableStateOf(false) }
    val audioPlaying = audioState?.isPlaying == true
    val audioDurationMs = audioState?.durationMs?.takeIf { it > 0 }
        ?: message.durationSeconds?.takeIf { it > 0 }?.times(1_000)
        ?: 0
    val audioPositionMs = audioState?.positionMs?.coerceIn(0, audioDurationMs.coerceAtLeast(0)) ?: 0
    LaunchedEffect(audioState?.positionMs, audioState?.durationMs, audioDurationMs) {
        if (!scrubbing) scrubPositionMs = audioPositionMs
    }
    val displayedPositionMs = if (scrubbing) {
        scrubPositionMs.coerceIn(0, audioDurationMs.coerceAtLeast(0))
    } else {
        audioPositionMs
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = message.memberAvatarUrl,
                    contentDescription = message.memberName,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                    loadCachedImmediately = true,
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.memberName, fontWeight = FontWeight.SemiBold)
                    Text(formatMessageDateTime(message.sentAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (translationEnabled && message.text?.isNotBlank() == true) {
                    IconButton(onClick = onRetranslate, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "重新翻译",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (message.type != MessageType.TEXT) {
                    IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Download, contentDescription = "保存到本地")
                    }
                }
            }

            message.text?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(substituteNickname(it, userNickname) ?: it)
                }
            }
            if (translationEnabled) {
                normalizeTranslationText(substituteNickname(message.text, userNickname), message.translation)?.let {
                    Spacer(Modifier.height(7.dp))
                    SelectionContainer {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                        )
                    }
                }
            }

            when (message.type) {
                MessageType.IMAGE, MessageType.VIDEO -> {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenMedia),
                    ) {
                        RemoteImage(
                            url = if (message.type == MessageType.IMAGE) {
                                message.mediaUrl ?: message.thumbnailUrl
                            } else {
                                message.thumbnailUrl ?: message.mediaUrl
                            },
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            preserveAspectRatio = true,
                            messageType = message.type,
                            message = message,
                        )
                        if (message.type == MessageType.VIDEO) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "播放视频",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }
                    }
                }

                MessageType.AUDIO -> {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        IconButton(onClick = onPlayVoice) {
                            Icon(
                                imageVector = if (audioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (audioPlaying) "暂停语音" else "播放语音",
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (message.isPlayed) "语音消息" else "未播放语音",
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    formatAudioTime(displayedPositionMs),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    " / ${formatAudioDuration(audioDurationMs)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = displayedPositionMs.toFloat(),
                                onValueChange = { value ->
                                    if (audioDurationMs > 0) {
                                        scrubbing = true
                                        scrubPositionMs = value.toInt()
                                    }
                                },
                                onValueChangeFinished = {
                                    if (audioDurationMs > 0) {
                                        VoicePlaybackService.seek(
                                            context = context,
                                            messageId = message.id,
                                            positionMs = scrubPositionMs,
                                        )
                                    }
                                    scrubbing = false
                                },
                                valueRange = 0f..audioDurationMs.coerceAtLeast(1).toFloat(),
                                enabled = audioDurationMs > 0,
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                            )
                        }
                        if (audioPlaying) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        context.startService(
                                            Intent(context, VoicePlaybackService::class.java).apply {
                                                action = VoicePlaybackService.ACTION_SET_SPEAKER
                                                putExtra(VoicePlaybackService.EXTRA_SPEAKER_ON, !(audioState?.speakerOn ?: false))
                                            }
                                        )
                                    }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_audio_speaker_official),
                                    contentDescription = if (audioState?.speakerOn == true) "切换到听筒" else "切换到扬声器",
                                    tint = if (audioState?.speakerOn == true) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                MessageType.TEXT -> Unit
            }
        }
    }
}

private fun formatAudioTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.getDefault(), totalSeconds / 60, totalSeconds % 60)
}

private fun formatAudioDuration(milliseconds: Int): String =
    if (milliseconds > 0) formatAudioTime(milliseconds) else "--:--"

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val initial = remember { AppGraph.settings.read() }
    var relayUrl by remember { mutableStateOf(initial.relayUrl) }
    var token by remember { mutableStateOf(initial.accessToken) }
    var openAiApiKey by remember { mutableStateOf(initial.openAiApiKey) }
    var openAiModel by remember { mutableStateOf(initial.openAiModel) }
    var modelOptions by remember { mutableStateOf(emptyList<String>()) }
    var translationEnabled by remember { mutableStateOf(initial.translationEnabled) }
    var userNickname by remember { mutableStateOf(initial.userNickname) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var modelFieldWidthPx by remember { mutableIntStateOf(0) }
    var savedLabel by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("") }
    var validatingApiKey by remember { mutableStateOf(false) }

    fun currentSettings() = AppSettings(
        relayUrl = relayUrl,
        accessToken = token,
        openAiApiKey = openAiApiKey,
        openAiModel = openAiModel,
        translationEnabled = translationEnabled,
        userNickname = userNickname,
    )

    fun saveTranslationSettings() {
        AppGraph.settings.save(currentSettings())
        TranslationManager.resetRetries()
        TranslationManager.enqueue(context)
        val key = openAiApiKey.trim()
        if (key.isBlank()) {
            modelStatus = "翻译设置已保存"
            return
        }
        scope.launch {
            modelStatus = "翻译设置已保存，正在加载官方模型..."
            TranslationManager.fetchAvailableModels(key)
                .onSuccess { models ->
                    modelOptions = buildList {
                        addAll(models)
                        if (openAiModel !in models) add(0, openAiModel)
                    }.distinct()
                    modelStatus = "翻译设置已保存，已加载 ${models.size} 个可用模型"
                }
                .onFailure { error ->
                    modelStatus = error.message ?: "翻译设置已保存，但模型加载失败"
                }
        }
    }

    fun validateApiKey() {
        val key = openAiApiKey.trim()
        if (key.isEmpty()) {
            modelStatus = "请先填写 OpenAI API Key"
            return
        }
        scope.launch {
            validatingApiKey = true
            modelStatus = "正在从 OpenAI 验证并加载模型..."
            val result = TranslationManager.fetchAvailableModels(key)
            validatingApiKey = false
            result.onSuccess { models ->
                modelOptions = models
                if (openAiModel.isBlank() && models.isNotEmpty()) {
                    openAiModel = models.first()
                }
                modelStatus = "API Key 有效，已加载 ${models.size} 个可用模型"
            }.onFailure { error ->
                modelStatus = error.message ?: "API Key 无效或模型加载失败"
            }
        }
    }

    LaunchedEffect(initial.openAiApiKey) {
        if (initial.openAiApiKey.isNotBlank()) {
            TranslationManager.fetchAvailableModels(initial.openAiApiKey)
                .onSuccess { models ->
                    modelOptions = models
                }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        item {
            Text("FCM 推送服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "应用启动或回到前台时会同步缺失的历史消息；保存后也会把本机 FCM Token 注册到 HTTPS 服务。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        item {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("同步服务地址") },
                placeholder = { Text("https://relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("访问令牌") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    AppGraph.settings.save(currentSettings())
                    savedLabel = "正在注册 FCM 设备..."
                    PushRegistrar.registerCurrentToken(context) { result ->
                        (context as? android.app.Activity)?.runOnUiThread {
                            savedLabel = result.fold(
                                onSuccess = { "设备已注册，系统推送已就绪" },
                                onFailure = { it.message ?: "FCM 设备注册失败" },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("保存并注册推送")
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("翻译", fontWeight = FontWeight.Medium)
                }
                Switch(
                    checked = translationEnabled,
                    onCheckedChange = {
                        translationEnabled = it
                        AppGraph.settings.save(currentSettings())
                        TranslationManager.resetRetries()
                        if (it) TranslationManager.enqueue(context)
                    },
                )
            }
        }
        item {
            OutlinedTextField(
                value = userNickname,
                onValueChange = { userNickname = it },
                label = { Text("你的称呼") },
                placeholder = { Text("用于替换消息中的 %%%") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = openAiApiKey,
                onValueChange = { openAiApiKey = it },
                label = { Text("OpenAI API Key") },
                placeholder = { Text("sk-...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = ::saveTranslationSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    Text("保存", maxLines = 1)
                }
                OutlinedButton(
                    onClick = ::validateApiKey,
                    enabled = !validatingApiKey,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    Text(if (validatingApiKey) "校验中..." else "校验有效性", maxLines = 1)
                }
            }
            if (modelStatus.isNotBlank()) {
                Text(modelStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        item {
            if (modelOptions.isNotEmpty()) {
                Box {
                    OutlinedTextField(
                        value = openAiModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("翻译模型") },
                        trailingIcon = {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择翻译模型")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { modelFieldWidthPx = it.width },
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { modelMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                        modifier = if (modelFieldWidthPx > 0) {
                            Modifier.width(with(density) { modelFieldWidthPx.toDp() })
                        } else {
                            Modifier
                        },
                    ) {
                        modelOptions.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    openAiModel = model
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            } else if (openAiApiKey.isNotBlank()) {
                Text(
                    "请先点击\"校验有效性\"以加载可用模型",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
        item {
            if (savedLabel.isNotBlank()) {
                Text(savedLabel, color = SignalGreen, fontSize = 13.sp)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
