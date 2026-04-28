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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════
// RENK SİSTEMİ — YG MUSIC v2
// ═══════════════════════════════════════════
val YG_Void       = Color(0xFF080A0F)
val YG_Abyss      = Color(0xFF0D1017)
val YG_Surface    = Color(0xFF131720)
val YG_Elevated   = Color(0xFF1C2230)
val YG_Rim        = Color(0xFF252D3D)
val YG_Muted      = Color(0xFF4A5568)
val YG_Ghost      = Color(0xFF718096)

val YG_Gold       = Color(0xFFFFBF00)
val YG_GoldGlow   = Color(0x33FFBF00)
val YG_GoldDim    = Color(0x99FFBF00)
val YG_Cyan       = Color(0xFF00D4FF)
val YG_CyanGlow   = Color(0x3300D4FF)
val YG_Smoke      = Color(0xFF9095A0)
val YG_SmokeGlow  = Color(0x339095A0)

val IG_Purple     = Color(0xFF833AB4)
val IG_Red        = Color(0xFFFD1D1D)
val IG_Orange     = Color(0xFFFCB045)

data class Song(val title: String, val artist: String, val path: String)

var mediaPlayer: MediaPlayer? = null
var globalMediaSession: MediaSessionCompat? = null
// ✅ MediaSession callback'lerinin Compose state'ini döngüye girmeden güncelleyebilmesi için global ref
var globalIsPlayingRef: androidx.compose.runtime.MutableState<Boolean>? = null

// ═══════════════════════════════════════════
// YARDIMCI FONKSİYONLAR
// ═══════════════════════════════════════════
fun themeAccent(mode: String) = when (mode) {
    "Jenerik" -> YG_Cyan
    "Efkarlı" -> YG_Smoke
    else       -> YG_Gold
}

fun themeGlow(mode: String) = when (mode) {
    "Jenerik" -> YG_CyanGlow
    "Efkarlı" -> YG_SmokeGlow
    else       -> YG_GoldGlow
}

class MainActivity : ComponentActivity() {

    private fun saveThemeMode(mode: String) {
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).edit().putString("theme_mode", mode).apply()
    }

    private fun getThemeMode(): String =
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).getString("theme_mode", "Normal") ?: "Normal"

    private fun getExiledPaths(): Set<String> =
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).getStringSet("exiled_songs", emptySet()) ?: emptySet()

    private fun exileSong(path: String) {
        val s = getExiledPaths().toMutableSet().also { it.add(path) }
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).edit().putStringSet("exiled_songs", s).apply()
    }

    private fun restoreSong(path: String) {
        val s = getExiledPaths().toMutableSet().also { it.remove(path) }
        getSharedPreferences("YG_Settings", Context.MODE_PRIVATE).edit().putStringSet("exiled_songs", s).apply()
    }

    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { onNotificationAction(it) }
        }
    }

    private var onNotificationAction: (String) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction("PLAY_PAUSE"); addAction("NEXT"); addAction("PREVIOUS")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(musicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(musicReceiver, filter)

        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            var hasPermission by remember { mutableStateOf(false) }
            var selectedMode by remember { mutableStateOf(getThemeMode()) }
            var exiledPaths by remember { mutableStateOf(getExiledPaths()) }

            val accent = themeAccent(selectedMode)
            val glow   = themeGlow(selectedMode)

            val songList = remember(hasPermission, exiledPaths) {
                if (hasPermission) fetchAudioFiles(context).filter { it.path !in exiledPaths } else emptyList()
            }
            val exiledSongsList = remember(exiledPaths) {
                fetchAudioFiles(context).filter { it.path in exiledPaths }
            }

            var currentIndex by remember { mutableIntStateOf(-1) }
            // ✅ _ip: global ref, isPlaying: okunabilir kısayol
            val _ip = remember { mutableStateOf(false) }
            globalIsPlayingRef = _ip
            var isPlaying by _ip
            var currentPosition by remember { mutableFloatStateOf(0f) }
            var songDuration by remember { mutableFloatStateOf(0f) }

            val currentSong = if (currentIndex in songList.indices) songList[currentIndex] else null

            val currentIndexState  = rememberUpdatedState(currentIndex)
            val songListState      = rememberUpdatedState(songList)
            val currentSongState   = rememberUpdatedState(currentSong)

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
                val idx   = currentIndexState.value
                val songs = songListState.value
                val song  = currentSongState.value
                when (action) {
                    "PLAY_PAUSE" -> {
                        val playing = mediaPlayer?.isPlaying ?: false
                        if (playing) mediaPlayer?.pause() else mediaPlayer?.start()
                        // ✅ Main thread garantisi — Compose state sadece UI thread'den yazılabilir
                        runOnUiThread {
                            isPlaying = !playing
                            song?.let { showNotification(context, it, !playing) }
                        }
                    }
                    "NEXT"     -> runOnUiThread { if (songs.isNotEmpty()) playAtIndex((idx + 1) % songs.size) }
                    "PREVIOUS" -> runOnUiThread { if (songs.isNotEmpty()) playAtIndex(if (idx <= 0) songs.size - 1 else idx - 1) }
                }
            }

            LaunchedEffect(isPlaying, currentIndex) {
                while (isPlaying) {
                    mediaPlayer?.let {
                        currentPosition = it.currentPosition.toFloat()
                        songDuration    = it.duration.toFloat()
                    }
                    delay(500)
                }
            }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
            LaunchedEffect(Unit) {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                launcher.launch(perm)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            Scaffold(
                topBar = { YGTopBar(accent) },
                bottomBar = {
                    Column {
                        AnimatedVisibility(
                            visible = currentSong != null,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            YGPlayerBar(
                                song = currentSong,
                                isPlaying = isPlaying,
                                position = currentPosition,
                                duration = songDuration,
                                accent = accent,
                                glow = glow,
                                onToggle = {
                                    if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                                    isPlaying = !isPlaying
                                    currentSong?.let { showNotification(context, it, isPlaying) }
                                },
                                onSeek = { pos ->
                                    mediaPlayer?.seekTo(pos.toInt())
                                    currentPosition = pos
                                    currentSong?.let { showNotification(context, it, isPlaying) }
                                },
                                onNext = { playAtIndex((currentIndex + 1) % songList.size) },
                                onPrevious = { playAtIndex(if (currentIndex <= 0) songList.size - 1 else currentIndex - 1) }
                            )
                        }
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val route = navBackStackEntry?.destination?.route
                        YGNavBar(currentRoute = route, accent = accent) { dest ->
                            if (route != dest) navController.navigate(dest) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                },
                containerColor = YG_Abyss
            ) { pad ->
                NavHost(navController, startDestination = "library", Modifier.padding(pad)) {
                    composable("explore")  { ExploreScreen(accent, glow) }
                    composable("library")  {
                        LibraryScreen(songList, currentSong, isPlaying, accent, glow,
                            onSongSelect = { i ->
                                if (currentIndex == i) {
                                    if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                                    isPlaying = !isPlaying
                                    currentSong?.let { showNotification(context, it, isPlaying) }
                                } else playAtIndex(i)
                            },
                            onExile = { song -> exileSong(song.path); exiledPaths = getExiledPaths() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            selectedMode = selectedMode,
                            onModeChange = { m -> selectedMode = m; saveThemeMode(m) },
                            exiledSongs = exiledSongsList,
                            onRestore = { p -> restoreSong(p); exiledPaths = getExiledPaths() }
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

// ═══════════════════════════════════════════
// TOP BAR
// ═══════════════════════════════════════════
@Composable
fun YGTopBar(accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(YG_Void, YG_Abyss)))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("YG", color = YG_Void, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text("Music", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
    }
}

// ═══════════════════════════════════════════
// NAV BAR
// ═══════════════════════════════════════════
@Composable
fun YGNavBar(currentRoute: String?, accent: Color, onNavigate: (String) -> Unit) {
    val items = listOf(
        Triple("explore",  Icons.Rounded.Explore,      "Keşfet"),
        Triple("library",  Icons.Rounded.LibraryMusic,  "Kitaplık"),
        Triple("settings", Icons.Rounded.Tune,          "Ayarlar")
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(YG_Void.copy(alpha = 0f), YG_Void)))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { (route, icon, label) ->
            val selected = currentRoute == route
            val scale by animateFloatAsState(if (selected) 1f else 0.9f, spring(dampingRatio = 0.5f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .scale(scale)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onNavigate(route) }
                    .padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (selected) accent.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = if (selected) accent else YG_Muted, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    color = if (selected) accent else YG_Muted,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// PLAYER BAR
// ═══════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YGPlayerBar(
    song: Song?,
    isPlaying: Boolean,
    position: Float,
    duration: Float,
    accent: Color,
    glow: Color,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    fun ms(v: Float): String { val s = (v / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    val density = LocalDensity.current.density

    val pulseAnim by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = if (isPlaying) 1.08f else 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(YG_Void.copy(alpha = 0f), YG_Void, YG_Void)))
    ) {
        var isSeeking by remember { mutableStateOf(false) }
        var seekValue by remember { mutableFloatStateOf(0f) }
        var barWidth by remember { mutableStateOf(0) }

        val displayPosition = if (isSeeking) seekValue else position
        val displayProgress = if (duration > 0f) (displayPosition / duration).coerceIn(0f, 1f) else 0f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(28.dp)
                .onSizeChanged { barWidth = it.width }
                .pointerInput(duration) {
                    detectTapGestures(
                        onPress = { offset ->
                            if (duration > 0f && barWidth > 0) {
                                val ratio = (offset.x / barWidth).coerceIn(0f, 1f)
                                val newPos = ratio * duration
                                isSeeking = false
                                onSeek(newPos)
                            }
                        }
                    )
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (duration > 0f && barWidth > 0) {
                                isSeeking = true
                                seekValue = ((offset.x / barWidth) * duration).coerceIn(0f, duration)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            if (duration > 0f && barWidth > 0) {
                                seekValue = ((change.position.x / barWidth) * duration).coerceIn(0f, duration)
                            }
                        },
                        onDragEnd = { onSeek(seekValue); isSeeking = false },
                        onDragCancel = { isSeeking = false }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YG_Rim)
            )
            Box(
                Modifier
                    .fillMaxWidth(displayProgress)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), accent)))
                    .align(Alignment.CenterStart)
            )
            if (barWidth > 0) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = (displayProgress * barWidth / density).dp)
                        .size(if (isSeeking) 15.dp else 11.dp)
                        .background(accent, CircleShape)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(ms(displayPosition), color = if (isSeeking) accent else YG_Muted, fontSize = 9.sp)
            Text(ms(duration), color = YG_Muted, fontSize = 9.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song?.artist ?: "", color = YG_Ghost, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.width(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = YG_Ghost, modifier = Modifier.size(22.dp))
                }
                Box(
                    modifier = Modifier
                        .scale(pulseAnim)
                        .size(50.dp)
                        .background(glow, CircleShape)
                        .padding(4.dp)
                        .background(accent, CircleShape)
                        .clip(CircleShape)
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        null,
                        tint = YG_Void,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.SkipNext, null, tint = YG_Ghost, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// KİTAPLIK EKRANI
// ═══════════════════════════════════════════
@Composable
fun LibraryScreen(
    songs: List<Song>,
    playingSong: Song?,
    isPlaying: Boolean,
    accent: Color,
    glow: Color,
    onSongSelect: (Int) -> Unit,
    onExile: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize().background(YG_Abyss), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.MusicOff, null, tint = YG_Muted, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Müzik bulunamadı", color = YG_Ghost, fontSize = 16.sp)
                Text("İzin ver ve yeniden dene", color = YG_Muted, fontSize = 12.sp)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(YG_Abyss),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("${songs.size} şarkı", color = YG_Muted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        itemsIndexed(songs) { index, song ->
            val isCurrent = playingSong?.path == song.path
            YGSongCard(song, isCurrent, isPlaying, accent, glow, { onSongSelect(index) }, { onExile(song) })
        }
    }
}

// ═══════════════════════════════════════════
// ŞARKI KARTI
// ═══════════════════════════════════════════
@Composable
fun YGSongCard(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    glow: Color,
    onClick: () -> Unit,
    onExileClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val cardScale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.5f))

    val barAnim1 by rememberInfiniteTransition(label = "b1").animateFloat(
        0.3f, 1f, label = "b1",
        animationSpec = infiniteRepeatable(tween(400 + (song.title.length * 7) % 300, easing = EaseInOutSine), RepeatMode.Reverse)
    )
    val barAnim2 by rememberInfiniteTransition(label = "b2").animateFloat(
        0.5f, 1f, label = "b2",
        animationSpec = infiniteRepeatable(tween(300 + (song.artist.length * 11) % 400, easing = EaseInOutSine), RepeatMode.Reverse)
    )
    val barAnim3 by rememberInfiniteTransition(label = "b3").animateFloat(
        0.2f, 0.9f, label = "b3",
        animationSpec = infiniteRepeatable(tween(500, easing = EaseInOutSine), RepeatMode.Reverse)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isCurrent)
                    Brush.horizontalGradient(listOf(glow.copy(alpha = 0.4f), YG_Surface))
                else
                    Brush.horizontalGradient(listOf(YG_Surface, YG_Surface))
            )
            .then(
                if (isCurrent) Modifier.background(
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent)),
                    RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() }
                )
            }
    ) {
        if (isCurrent) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            )
        }

        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isCurrent) accent.copy(alpha = 0.15f) else YG_Elevated,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent && isPlaying) {
                    Row(
                        Modifier.size(28.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        listOf(barAnim1, barAnim2, barAnim3).forEach { h ->
                            Box(Modifier.width(5.dp).fillMaxHeight(h).background(accent, RoundedCornerShape(2.dp)))
                        }
                    }
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = if (isCurrent) accent else YG_Muted, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = if (isCurrent) accent else Color.White,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artist,
                    color = if (isCurrent) accent.copy(alpha = 0.6f) else YG_Ghost,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.MoreVert, null, tint = YG_Muted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(YG_Elevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Sürgüne Gönder", color = Color.White, fontSize = 13.sp) },
                        onClick = { onExileClick(); showMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.Block, null, tint = Color(0xFFFF5555), modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// KEŞFET EKRANI
// ═══════════════════════════════════════════
@Composable
fun ExploreScreen(accent: Color, glow: Color) {
    Box(
        modifier = Modifier.fillMaxSize().background(YG_Abyss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                Modifier
                    .size(100.dp)
                    .background(glow, CircleShape)
                    .padding(6.dp)
                    .background(accent.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Explore, null, tint = accent, modifier = Modifier.size(48.dp))
            }

            Spacer(Modifier.height(28.dp))
            Text("KEŞFET", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Spacer(Modifier.height(8.dp))
            Text("Çok Yakında", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Dünyanın her köşesinden müzik\nparmaklarının ucuna gelecek.",
                color = YG_Ghost, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp
            )
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Pop", "Rock", "Efkar", "HipHop").forEach { tag ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(accent.copy(alpha = 0.1f))
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Text(tag, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// AYARLAR EKRANI
// ═══════════════════════════════════════════
const val CURRENT_VERSION = "2.0"
const val VERSION_URL = "https://raw.githubusercontent.com/G3C3C1/my-music-player/main/version.json"
const val WEBSITE_URL  = "https://ygmusic.online/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedMode: String,
    onModeChange: (String) -> Unit,
    exiledSongs: List<Song>,
    onRestore: (String) -> Unit
) {
    var showExileSheet   by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val accent  = themeAccent(selectedMode)

    val motto = when (selectedMode) {
        "Efkarlı" -> "Gece uzun, sigara kısa, dert derin...\nBazı şarkılar teselli etmez — sadece eşlik eder. 🌧"
        "Jenerik" -> "Frekanslar değişti, sinyal güçlü.\nBiz zaten yarındayız, dünya henüz yetişemedi. ⚡"
        else       -> "Ne süslü, ne sönük — tam kıvamında.\nYG klasiği: altın gibi, değer düşmez. 🟡"
    }

    // ✅ FIX 2: Dispatchers.IO ile network isteği yap, Main thread'e dönerek state güncelle
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val url  = java.net.URL(VERSION_URL)
                val json = url.readText()
                val remote = json.trimIndent()
                    .removePrefix("{")
                    .removeSuffix("}")
                    .split(":")
                    .lastOrNull()
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?: return@withContext
                // ✅ State güncellemesini Main thread'de yap
                withContext(Dispatchers.Main) {
                    latestVersion = remote
                    if (remote != CURRENT_VERSION) showUpdateDialog = true
                }
            } catch (e: Exception) {
                // İnternet yoksa sessizce geç
            }
        }
    }

    // Güncelleme diyalogu
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            containerColor = YG_Elevated,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(accent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = accent, modifier = Modifier.size(26.dp))
                }
            },
            title = {
                Text(
                    "Güncelleme Mevcut 🎉",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "YG Music'in yeni bir sürümü çıktı!\nŞu anki: v$CURRENT_VERSION  →  Yeni: v${latestVersion ?: "?"}",
                        color = YG_Ghost,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Güncellemeyi yapman tavsiye edilir, yeni özellikler ve düzeltmeler seni bekliyor 🙌",
                        color = YG_Muted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent)
                        .clickable {
                            showUpdateDialog = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)))
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Evet, güncelle! 🚀", color = YG_Void, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(YG_Surface)
                        .clickable { showUpdateDialog = false }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Şimdi değil", color = YG_Ghost, fontSize = 14.sp)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(YG_Abyss),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── Başlık ──
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(YG_Void, YG_Abyss)))
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Text("GÖRÜNÜM", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            }
        }

        // ── Mod butonları ──
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Efkarlı" to YG_Smoke, "Jenerik" to YG_Cyan, "Normal" to YG_Gold).forEach { (mode, color) ->
                    YGModeButton(
                        text = mode, isSelected = selectedMode == mode, color = color,
                        modifier = Modifier.weight(1f), onClick = { onModeChange(mode) }
                    )
                }
            }
        }

        // ── Motto ──
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = motto,
                color = accent.copy(alpha = 0.75f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(accent.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
            Spacer(Modifier.height(20.dp))
        }

        // ── Bağlantılar başlığı ──
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(YG_Rim))
                Text("  bağlantılar  ", color = YG_Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Box(Modifier.weight(1f).height(1.dp).background(YG_Rim))
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Instagram kartı ──
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(IG_Purple, IG_Red, IG_Orange),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                    )
                    .clickable {
                        val uri = Uri.parse("http://instagram.com/_u/gececi.yusuf")
                        val i = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.instagram.android") }
                        try { context.startActivity(i) }
                        catch (e: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://instagram.com/gececi.yusuf"))) }
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("efendisi Dobby'e corap verdi", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                        Text("@gececi.yusuf", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Icon(Icons.Rounded.OpenInNew, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── Website kartı ──
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(YG_Gold.copy(alpha = 0.8f), Color(0xFFFF8C00)),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    )
                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))) }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).background(YG_Void.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Language, null, tint = YG_Void, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Resmi Web Sitesi", color = YG_Void.copy(alpha = 0.75f), fontSize = 11.sp)
                        Text("ygmusic.online", color = YG_Void, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Icon(Icons.Rounded.OpenInNew, null, tint = YG_Void.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Liste başlığı ──
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(YG_Rim))
                Text("  liste  ", color = YG_Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Box(Modifier.weight(1f).height(1.dp).background(YG_Rim))
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Sürgündekiler kartı ──
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(YG_Surface)
                    .clickable { showExileSheet = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Block, null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sürgündekiler", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${exiledSongs.size} şarkı sürgünde", color = YG_Ghost, fontSize = 12.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = YG_Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showExileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExileSheet = false },
            containerColor = YG_Elevated,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Block, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Sürgün Listesi", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("${exiledSongs.size} şarkı", color = YG_Ghost, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))

                if (exiledSongs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("Burada kimse yok 👻", color = YG_Ghost)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(exiledSongs) { _, song ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(YG_Surface, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = YG_Ghost, fontSize = 11.sp)
                                }
                                IconButton(onClick = { onRestore(song.path) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Restore, null, tint = accent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YGModeButton(text: String, isSelected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isSelected) 1f else 0.95f, spring(dampingRatio = 0.6f))
    Box(
        modifier = modifier
            .height(52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) color.copy(alpha = 0.15f) else YG_Surface)
            .then(
                if (isSelected) Modifier.background(
                    Brush.verticalGradient(listOf(color.copy(alpha = 0.05f), Color.Transparent)),
                    RoundedCornerShape(14.dp)
                ) else Modifier
            )
            .clickable { onClick() }
            .padding(if (isSelected) 1.dp else 0.dp)
            .then(
                if (isSelected) Modifier.background(color.copy(alpha = 0.0f), RoundedCornerShape(13.dp)) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                color = if (isSelected) color else YG_Ghost,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(if (isSelected) 5.dp else 3.dp)
                    .background(if (isSelected) color else YG_Muted, CircleShape)
            )
        }
    }
}

// ═══════════════════════════════════════════
// ÇEKIRDEK FONKSİYONLAR
// ═══════════════════════════════════════════
fun playMusic(path: String) {
    mediaPlayer?.stop(); mediaPlayer?.release()
    mediaPlayer = MediaPlayer().apply { setDataSource(path); prepare(); start() }
}

// ═══════════════════════════════════════════
// showNotification
// ═══════════════════════════════════════════
fun showNotification(context: Context, song: Song, isPlaying: Boolean) {
    val channelId = "music_channel"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Music Control", NotificationManager.IMPORTANCE_LOW)
        )

    if (globalMediaSession == null) {
        globalMediaSession = MediaSessionCompat(context, "YG_Music_Session").apply {

            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            val activityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            setSessionActivity(
                PendingIntent.getActivity(
                    context, 0, activityIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            isActive = true

            setCallback(object : MediaSessionCompat.Callback() {

                private fun updateState(playing: Boolean) {
                    val mp = mediaPlayer ?: return
                    val nowState = if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
                    val speed = if (playing) 1f else 0f
                    globalMediaSession?.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setActions(
                                PlaybackStateCompat.ACTION_PLAY or
                                        PlaybackStateCompat.ACTION_PAUSE or
                                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                        PlaybackStateCompat.ACTION_SEEK_TO
                            )
                            .setState(nowState, mp.currentPosition.toLong(), speed)
                            .build()
                    )
                }

                override fun onPlay() {
                    mediaPlayer?.start()
                    updateState(true)
                    // ✅ Broadcast yok — döngü olmaz. State direkt güncellenir.
                    globalIsPlayingRef?.value = true
                }

                override fun onPause() {
                    mediaPlayer?.pause()
                    updateState(false)
                    // ✅ Broadcast yok — döngü olmaz. State direkt güncellenir.
                    globalIsPlayingRef?.value = false
                }

                override fun onSkipToNext() {
                    context.sendBroadcast(
                        Intent("NEXT").apply { setPackage(context.packageName) }
                    )
                }

                override fun onSkipToPrevious() {
                    context.sendBroadcast(
                        Intent("PREVIOUS").apply { setPackage(context.packageName) }
                    )
                }

                override fun onSeekTo(pos: Long) {
                    mediaPlayer?.seekTo(pos.toInt())
                    val mp = mediaPlayer ?: return
                    val playing = mp.isPlaying
                    val nowState = if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
                    val speed = if (playing) 1f else 0f
                    globalMediaSession?.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setActions(
                                PlaybackStateCompat.ACTION_PLAY or
                                        PlaybackStateCompat.ACTION_PAUSE or
                                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                        PlaybackStateCompat.ACTION_SEEK_TO
                            )
                            .setState(nowState, pos, speed)
                            .build()
                    )
                }
            })
        }
    }

    val mediaSession = globalMediaSession!!

    val durationMs = mediaPlayer?.duration?.toLong() ?: 0L
    mediaSession.setMetadata(
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,  durationMs)
            .build()
    )

    val positionMs    = mediaPlayer?.currentPosition?.toLong() ?: 0L
    val state         = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
    val playbackSpeed = if (isPlaying) 1f else 0f
    mediaSession.setPlaybackState(
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, positionMs, playbackSpeed)
            .build()
    )

    fun pi(action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(action).apply { setPackage(context.packageName) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(song.title)
        .setContentText(song.artist)
        .setOngoing(isPlaying)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession.sessionToken)
        )
        .addAction(android.R.drawable.ic_media_previous, "Geri",  pi("PREVIOUS"))
        .addAction(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            "Oynat", pi("PLAY_PAUSE")
        )
        .addAction(android.R.drawable.ic_media_next, "İleri", pi("NEXT"))
        .build()

    manager.notify(1, notification)
}

fun fetchAudioFiles(context: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val cursor = context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA),
        "${MediaStore.Audio.Media.DURATION} > 0",
        null, null
    )
    cursor?.use {
        val tIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val aIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val pIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        while (it.moveToNext())
            songs.add(Song(it.getString(tIdx) ?: "Bilinmeyen", it.getString(aIdx) ?: "Bilinmeyen", it.getString(pIdx) ?: ""))
    }
    return songs
}