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
import android.support.v4.media.session.MediaSessionCompat
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.navigation.compose.*
import kotlinx.coroutines.delay

// --- TASARIM SİSTEMİ RENKLERİ ---
val YG_Black = Color(0xFF121212)
val YG_Cyan = Color(0xFF00E5FF)
val YG_Gold = Color(0xFFFFC107)
val YG_Efkar = Color(0xFF757575)
val YG_Gray = Color(0xFF888888)
val YG_CardBG = Color(0xFF1A1A1A)
val YG_BarBG = Color(0xFF1D1D1D)
val YG_DarkBG = Color(0xFF080B10)

data class Song(val title: String, val artist: String, val path: String)

var mediaPlayer: MediaPlayer? = null

class MainActivity : ComponentActivity() {

    // --- HAFIZA FONKSİYONLARI ---
    private fun saveThemeMode(mode: String) {
        val sharedPref = getSharedPreferences("YG_Settings", Context.MODE_PRIVATE)
        sharedPref.edit().putString("theme_mode", mode).apply()
    }

    private fun getThemeMode(): String {
        val sharedPref = getSharedPreferences("YG_Settings", Context.MODE_PRIVATE)
        return sharedPref.getString("theme_mode", "Normal") ?: "Normal"
    }

    private fun getExiledPaths(): Set<String> {
        val sharedPref = getSharedPreferences("YG_Settings", Context.MODE_PRIVATE)
        return sharedPref.getStringSet("exiled_songs", emptySet()) ?: emptySet()
    }

    private fun exileSong(path: String) {
        val currentExiled = getExiledPaths().toMutableSet()
        currentExiled.add(path)
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).edit().putStringSet("exiled_songs", currentExiled).apply()
    }

    private fun restoreSong(path: String) {
        val currentExiled = getExiledPaths().toMutableSet()
        currentExiled.remove(path)
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).edit().putStringSet("exiled_songs", currentExiled).apply()
    }

    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action -> onNotificationAction(action) }
        }
    }

    private var onNotificationAction: (String) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction("PLAY_PAUSE"); addAction("NEXT"); addAction("PREVIOUS")
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

            var selectedMode by remember { mutableStateOf(getThemeMode()) }
            var exiledPaths by remember { mutableStateOf(getExiledPaths()) }

            val currentThemeColor = when(selectedMode) {
                "Jenerik" -> YG_Cyan
                "Efkarlı" -> YG_Efkar
                "Normal" -> YG_Gold
                else -> YG_Gold
            }

            val songList = remember(hasPermission, exiledPaths) {
                if (hasPermission) fetchAudioFiles(context).filter { it.path !in exiledPaths } else emptyList()
            }

            val exiledSongsList = remember(exiledPaths) {
                fetchAudioFiles(context).filter { it.path in exiledPaths }
            }

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
                    mediaPlayer?.setOnCompletionListener { playAtIndex((index + 1) % songList.size) }
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
                    mediaPlayer?.let { currentPosition = it.currentPosition.toFloat(); songDuration = it.duration.toFloat() }
                    delay(1000)
                }
            }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
            LaunchedEffect(Unit) {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                launcher.launch(permission)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            Scaffold(
                topBar = { TopBarDesign(currentThemeColor) },
                bottomBar = {
                    Column {
                        AnimatedVisibility(visible = currentSong != null) {
                            BottomPlayerBar(
                                song = currentSong, isPlaying = isPlaying, position = currentPosition,
                                duration = songDuration, themeColor = currentThemeColor,
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
                                icon = { Icon(Icons.Default.MusicNote, null) }, label = { Text("Kitaplık") },
                                selected = currentRoute == "library",
                                onClick = { if (currentRoute != "library") navController.navigate("library") { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = currentThemeColor, selectedTextColor = currentThemeColor, unselectedIconColor = YG_Gray, indicatorColor = Color.Transparent)
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ayarlar") },
                                selected = currentRoute == "settings",
                                onClick = { if (currentRoute != "settings") navController.navigate("settings") { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = currentThemeColor, selectedTextColor = currentThemeColor, unselectedIconColor = YG_Gray, indicatorColor = Color.Transparent)
                            )
                        }
                    }
                },
                containerColor = YG_Black
            ) { innerPadding ->
                NavHost(navController, startDestination = "library", Modifier.padding(innerPadding)) {
                    composable("library") {
                        LibraryScreen(songList, currentSong, isPlaying, currentThemeColor,
                            onSongSelect = { index ->
                                if (currentIndex == index) {
                                    if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                                    isPlaying = !isPlaying
                                    currentSong?.let { showNotification(context, it, isPlaying) }
                                } else { playAtIndex(index) }
                            },
                            onExile = { song -> exileSong(song.path); exiledPaths = getExiledPaths() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            selectedMode = selectedMode,
                            onModeChange = { newMode -> selectedMode = newMode; saveThemeMode(newMode) },
                            exiledSongs = exiledSongsList,
                            onRestore = { path -> restoreSong(path); exiledPaths = getExiledPaths() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(musicReceiver) } catch (e: Exception) { e.printStackTrace() }
    }
}

// --- AYARLAR EKRANI ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(selectedMode: String, onModeChange: (String) -> Unit, exiledSongs: List<Song>, onRestore: (String) -> Unit) {
    var showExileSheet by remember { mutableStateOf(false) }

    val motto = when (selectedMode) {
        "Efkarlı" -> "Dertler derya olmuş, bizde bir sandal... 🚬"
        "Jenerik" -> "Gelecekten geliyoruz, her şey dijital! ⚡"
        "Normal" -> "Klasik YG tarzı, sadelik ve güç. 🟡"
        else -> ""
    }

    val accentColor = when (selectedMode) {
        "Efkarlı" -> YG_Efkar
        "Jenerik" -> YG_Cyan
        "Normal" -> YG_Gold
        else -> YG_Gold
    }

    Column(modifier = Modifier.fillMaxSize().background(YG_DarkBG).padding(20.dp)) {
        Text(text = "Görünüm Modu", color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Efkarlı", "Jenerik", "Normal").forEach { mode ->
                val btnColor = if (mode == "Efkarlı") YG_Efkar else if (mode == "Jenerik") YG_Cyan else YG_Gold
                AppearanceModeButton(text = mode, isSelected = selectedMode == mode, accentColor = btnColor, modifier = Modifier.weight(1f), onClick = { onModeChange(mode) })
            }
        }

        Text(text = motto, color = accentColor.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp), fontWeight = FontWeight.Medium)

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
        Spacer(Modifier.height(24.dp))

        Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { showExileSheet = true }, color = YG_CardBG) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Gavel, null, tint = accentColor)
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sürgündekiler", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("${exiledSongs.size} şarkı sürgünde", color = YG_Gray, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = YG_Gray)
            }
        }
    }

    if (showExileSheet) {
        ModalBottomSheet(onDismissRequest = { showExileSheet = false }, containerColor = YG_BarBG) {
            Column(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 400.dp)) {
                Text("Sürgün Listesi", color = accentColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                if (exiledSongs.isEmpty()) {
                    Text("Burada kimse yok...", color = YG_Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(exiledSongs) { _, song ->
                            Row(Modifier.fillMaxWidth().background(YG_CardBG, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = YG_Gray, fontSize = 11.sp)
                                }
                                IconButton(onClick = { onRestore(song.path) }) {
                                    Icon(Icons.Default.SettingsBackupRestore, "Geri Getir", tint = accentColor)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun AppearanceModeButton(text: String, isSelected: Boolean, accentColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(50.dp).clip(RoundedCornerShape(12.dp)).clickable { onClick() },
        color = if (isSelected) accentColor.copy(alpha = 0.15f) else YG_CardBG,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, accentColor) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = text, color = if (isSelected) accentColor else Color.White.copy(alpha = 0.6f), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
            if (isSelected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).background(accentColor, CircleShape))
        }
    }
}

@Composable
fun TopBarDesign(themeColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().background(YG_Black).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(themeColor), contentAlignment = Alignment.Center) {
            Text(text = "YG", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text = "Music", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomPlayerBar(song: Song?, isPlaying: Boolean, position: Float, duration: Float, themeColor: Color, onToggle: () -> Unit, onSeek: (Float) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    fun formatTime(ms: Float): String {
        val totalSeconds = (ms / 1000).toInt()
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
    Column(modifier = Modifier.fillMaxWidth().background(YG_BarBG)) {
        HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.15f))
        Box(modifier = Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = position, onValueChange = onSeek, valueRange = 0f..(if (duration > 0) duration else 1f),
                modifier = Modifier.fillMaxWidth(),
                thumb = { Spacer(modifier = Modifier.size(8.dp).background(themeColor, CircleShape)) },
                track = {
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.DarkGray.copy(alpha = 0.5f))) {
                        Box(modifier = Modifier.fillMaxWidth((position / (if (duration > 0) duration else 1f))).height(2.dp).background(themeColor))
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
                Surface(modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onToggle() }, color = themeColor) {
                    Box(contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(28.dp)) }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
            }
        }
    }
}

@Composable
fun LibraryScreen(songs: List<Song>, playingSong: Song?, isPlaying: Boolean, themeColor: Color, onSongSelect: (Int) -> Unit, onExile: (Song) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs) { index, song ->
            val isCurrent = playingSong?.path == song.path
            SongCard(song, isCurrent, isPlaying, themeColor, { onSongSelect(index) }, { onExile(song) })
        }
    }
}

@Composable
fun SongCard(song: Song, isCurrent: Boolean, isPlaying: Boolean, themeColor: Color, onClick: () -> Unit, onExileClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onClick() }, color = if (isCurrent) Color(0xFF252525) else YG_CardBG) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(52.dp), color = if (isCurrent) themeColor else Color(0xFF252525), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp), tint = if (isCurrent) Color.Black else themeColor)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = if (isCurrent) themeColor else Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = YG_Gray, fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Daha Fazla", tint = YG_Gray) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(YG_BarBG)) {
                    DropdownMenuItem(text = { Text("Sürgüne Gönder", color = Color.White) }, onClick = { onExileClick(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Gavel, null, tint = Color.Red) })
                }
            }
            Icon(if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = themeColor)
        }
    }
}

fun playMusic(path: String) {
    mediaPlayer?.stop(); mediaPlayer?.release()
    mediaPlayer = MediaPlayer().apply { setDataSource(path); prepare(); start() }
}

fun showNotification(context: Context, song: Song, isPlaying: Boolean) {
    val channelId = "music_channel"; val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val mediaSession = MediaSessionCompat(context, "YG_Music_Session")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(channelId, "Music Control", NotificationManager.IMPORTANCE_LOW))
    fun pi(action: String) = PendingIntent.getBroadcast(context, action.hashCode(), Intent(action).apply { setPackage(context.packageName) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(song.title).setContentText(song.artist).setOngoing(isPlaying).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2).setMediaSession(mediaSession.sessionToken)).addAction(android.R.drawable.ic_media_previous, "Geri", pi("PREVIOUS")).addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Oynat", pi("PLAY_PAUSE")).addAction(android.R.drawable.ic_media_next, "İleri", pi("NEXT")).build()
    manager.notify(1, notification)
}

fun fetchAudioFiles(context: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val cursor = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA), "${MediaStore.Audio.Media.DURATION} > 0", null, null)
    cursor?.use {
        val tIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val aIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val pIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        while (it.moveToNext()) songs.add(Song(it.getString(tIdx) ?: "Bilinmeyen", it.getString(aIdx) ?: "Bilinmeyen", it.getString(pIdx) ?: ""))
    }
    return songs
}