package com.ygmusic.stream

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.navigation.compose.*
import kotlinx.coroutines.delay

// --- TASARIM SİSTEMİ ---
val YG_Black = Color(0xFF121212)
val YG_Gold = Color(0xFFFFC107)
val YG_Gray = Color(0xFF888888)
val YG_CardBG = Color(0xFF1A1A1A)
val YG_BarBG = Color(0xFF1D1D1D)

data class Song(val title: String, val artist: String, val path: String)

var mediaPlayer: MediaPlayer? = null

class MainActivity : ComponentActivity() {

    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                onNotificationAction(action)
            }
        }
    }

    private var onNotificationAction: (String) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction("PLAY_PAUSE")
            addAction("NEXT")
            addAction("PREVIOUS")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(musicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(musicReceiver, filter)
        }

        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            var hasPermission by remember { mutableStateOf(false) }
            val songList = remember(hasPermission) { if (hasPermission) fetchAudioFiles(context) else emptyList() }

            var currentIndex by remember { mutableIntStateOf(-1) }
            var isPlaying by remember { mutableStateOf(false) }
            var currentPosition by remember { mutableFloatStateOf(0f) }
            var songDuration by remember { mutableFloatStateOf(0f) }

            val currentSong = if (currentIndex in songList.indices) songList[currentIndex] else null

            fun playAtIndex(index: Int) {
                if (index in songList.indices) {
                    currentIndex = index
                    playMusic(songList[index].path)
                    isPlaying = true
                    mediaPlayer?.setOnCompletionListener {
                        playAtIndex((index + 1) % songList.size)
                    }
                    showNotification(context, songList[index], true)
                }
            }

            onNotificationAction = { action ->
                when (action) {
                    "PLAY_PAUSE" -> {
                        if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                        isPlaying = !isPlaying
                        currentSong?.let { showNotification(context, it, isPlaying) }
                    }
                    "NEXT" -> playAtIndex((currentIndex + 1) % songList.size)
                    "PREVIOUS" -> playAtIndex(if (currentIndex <= 0) songList.size - 1 else currentIndex - 1)
                }
            }

            LaunchedEffect(isPlaying, currentIndex) {
                while (isPlaying) {
                    mediaPlayer?.let {
                        currentPosition = it.currentPosition.toFloat()
                        songDuration = it.duration.toFloat()
                    }
                    delay(1000)
                }
            }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
            LaunchedEffect(Unit) {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                launcher.launch(permission)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            Scaffold(
                topBar = { TopBarDesign() },
                bottomBar = {
                    Column {
                        AnimatedVisibility(visible = currentSong != null) {
                            BottomPlayerBar(
                                song = currentSong, isPlaying = isPlaying, position = currentPosition, duration = songDuration,
                                onToggle = {
                                    if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                                    isPlaying = !isPlaying
                                    currentSong?.let { showNotification(context, it, isPlaying) }
                                },
                                onSeek = { mediaPlayer?.seekTo(it.toInt()); currentPosition = it },
                                onNext = { playAtIndex((currentIndex + 1) % songList.size) },
                                onPrevious = { playAtIndex(if (currentIndex <= 0) songList.size - 1 else currentIndex - 1) }
                            )
                        }

                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = navBackStackEntry?.destination?.route

                        NavigationBar(containerColor = YG_Black) {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.MusicNote, null) },
                                label = { Text("Kitaplık") },
                                selected = currentRoute == "library",
                                onClick = {
                                    if (currentRoute != "library") {
                                        navController.navigate("library") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = YG_Gold, selectedTextColor = YG_Gold, unselectedIconColor = YG_Gray, indicatorColor = Color.Transparent)
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text("Ayarlar") },
                                selected = currentRoute == "settings",
                                onClick = {
                                    if (currentRoute != "settings") {
                                        navController.navigate("settings") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = YG_Gold, selectedTextColor = YG_Gold, unselectedIconColor = YG_Gray, indicatorColor = Color.Transparent)
                            )
                        }
                    }
                },
                containerColor = YG_Black
            ) { innerPadding ->
                NavHost(navController, startDestination = "library", Modifier.padding(innerPadding)) {
                    composable("library") {
                        LibraryScreen(songList, currentSong, isPlaying) { index ->
                            if (currentIndex == index) {
                                if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                                isPlaying = !isPlaying
                                currentSong?.let { showNotification(context, it, isPlaying) }
                            } else {
                                playAtIndex(index)
                            }
                        }
                    }
                    composable("settings") { SettingsScreen() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(musicReceiver) } catch (e: Exception) { e.printStackTrace() }
    }
}

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().background(YG_Black).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Settings, null, tint = YG_Gold, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Ayarlar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Sürüm 1.0", color = YG_Gray, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomPlayerBar(song: Song?, isPlaying: Boolean, position: Float, duration: Float, onToggle: () -> Unit, onSeek: (Float) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    fun formatTime(ms: Float): String {
        val totalSeconds = (ms / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    Column(modifier = Modifier.fillMaxWidth().background(YG_BarBG)) {
        HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.15f))
        Box(modifier = Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = position, onValueChange = onSeek, valueRange = 0f..(if (duration > 0) duration else 1f),
                modifier = Modifier.fillMaxWidth(),
                thumb = { Spacer(modifier = Modifier.size(8.dp).background(YG_Gold, CircleShape)) },
                track = {
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.DarkGray.copy(alpha = 0.5f))) {
                        Box(modifier = Modifier.fillMaxWidth((position / (if (duration > 0) duration else 1f))).height(2.dp).background(YG_Gold))
                    }
                }
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = YG_Gray, fontSize = 9.sp)
            Text(formatTime(duration), color = YG_Gray, fontSize = 9.sp)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(song?.title ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song?.artist ?: "", color = YG_Gray, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                Surface(modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onToggle() }, color = YG_Gold) {
                    Box(contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(28.dp)) }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
            }
        }
    }
}

fun playMusic(path: String) {
    mediaPlayer?.stop()
    mediaPlayer?.release()
    mediaPlayer = MediaPlayer().apply {
        setDataSource(path)
        prepare()
        start()
    }
}

@Composable
fun TopBarDesign() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(YG_Black)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sarı daire içindeki YG
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(YG_Gold),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "YG",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.width(12.dp))

        // Yanındaki MUSIC yazısı
        Text(
            text = "Music",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp )
    }
}

@Composable
fun LibraryScreen(songs: List<Song>, playingSong: Song?, isPlaying: Boolean, onSongSelect: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs) { index, song ->
            val isCurrent = playingSong?.path == song.path
            SongCard(song, isCurrent, isPlaying) { onSongSelect(index) }
        }
    }
}

@Composable
fun SongCard(song: Song, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onClick() }, color = if (isCurrent) Color(0xFF252525) else YG_CardBG) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(52.dp), color = if (isCurrent) YG_Gold else Color(0xFF252525), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp), tint = if (isCurrent) Color.Black else YG_Gold) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = if (isCurrent) YG_Gold else Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = YG_Gray, fontSize = 12.sp)
            }
            Icon(if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = YG_Gold)
        }
    }
}

fun showNotification(context: Context, song: Song, isPlaying: Boolean) {
    val channelId = "music_channel"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, "Music Control", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply { setPackage(context.packageName) }
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(song.title)
        .setContentText(song.artist)
        .setOngoing(isPlaying)
        .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        .addAction(android.R.drawable.ic_media_previous, "Geri", getPendingIntent("PREVIOUS"))
        .addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Oynat", getPendingIntent("PLAY_PAUSE"))
        .addAction(android.R.drawable.ic_media_next, "İleri", getPendingIntent("NEXT"))
        .build()

    manager.notify(1, notification)
}

fun fetchAudioFiles(context: android.content.Context): List<Song> {
    val songs = mutableListOf<Song>()
    val projection = arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA)
    val cursor = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.DURATION} > 0", null, null)
    cursor?.use {
        val tIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val aIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val pIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        while (it.moveToNext()) {
            songs.add(Song(it.getString(tIdx) ?: "Bilinmeyen", it.getString(aIdx) ?: "Bilinmeyen", it.getString(pIdx) ?: ""))
        }
    }
    return songs
}