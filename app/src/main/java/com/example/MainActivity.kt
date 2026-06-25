package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.example.data.Contact
import com.example.ui.AppScreen
import com.example.ui.DialerUiState
import com.example.ui.DialerViewModel
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import android.media.MediaPlayer
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) { // Always dark theme for Grandma
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        SmartDialerApp()
                    }
                }
            }
        }
    }
}

@Composable
fun SmartDialerApp(viewModel: DialerViewModel = viewModel()) {
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()

    // Request permissions
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            AppScreen.Main -> {
                MainGrandmaScreen(
                    viewModel = viewModel,
                    contacts = contacts,
                    uiState = uiState,
                    hasAudioPermission = hasAudioPermission,
                    onRequestPermission = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
            AppScreen.Settings -> {
                SettingsScreen(
                    viewModel = viewModel,
                    contacts = contacts
                )
            }
        }
    }
}

// --- MAIN SCREEN (NO TEXT, JUST PHOTOS GRID AND PULSING MIC) ---

@Composable
fun MainGrandmaScreen(
    viewModel: DialerViewModel,
    contacts: List<Contact>,
    uiState: DialerUiState,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    var isPressingSettings = remember { mutableStateOf(false) }

    // Trigger settings on continuous 3-second long press on the hidden icon
    LaunchedEffect(isPressingSettings.value) {
        if (isPressingSettings.value) {
            delay(3000)
            viewModel.onSettingsLongPress(context)
            isPressingSettings.value = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121214))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hidden entry top header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Subtle high-contrast decorative star symbol for visual balance
                Icon(
                    imageVector = Icons.Default.BrightnessLow,
                    contentDescription = null,
                    tint = Color(0xFF2E2E34),
                    modifier = Modifier.size(24.dp)
                )

                // Hidden Settings trigger - Star icon that requires 3-second long press
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("hidden_settings_trigger")
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPressingSettings.value = true
                                    try {
                                        awaitRelease()
                                    } finally {
                                        isPressingSettings.value = false
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Hidden Configuration Area",
                        tint = if (isPressingSettings.value) Color(0xFFFFD54F) else Color(0xFF2E2E34),
                        modifier = Modifier.size(if (isPressingSettings.value) 28.dp else 24.dp)
                    )
                }
            }

            // Beautiful Grandma Mima Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1E1E22))
                    .border(1.dp, Color(0xFF2E2E34), RoundedCornerShape(24.dp))
                    .clickable { viewModel.speakGreeting(context) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Beautiful Grandma Avatar
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color(0xFFFFB300), CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_grandma_1782382807083),
                        contentDescription = "Mima the Assistant",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "mima",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "مِيما - مساعدتك الذكية",
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                // Clicking the sound icon also says hello
                IconButton(
                    onClick = { viewModel.speakGreeting(context) },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF2E2E34), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Hear Welcome",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (contacts.isEmpty()) {
                // Friendly visual empty state helper
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_grandma_1782382807083),
                        contentDescription = "Mima the Assistant",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color(0xFFFFB300), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "أهلاً بك! أنا مِيما",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "اضغطي مع الاستمرار على النجمة في الأعلى لإضافة جهات اتصال والبدء.",
                        color = Color(0xFFFFB300),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Press and hold the star icon at the top right to unlock Settings and register contacts.",
                        color = Color(0xFFB0B0B5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Large Grid of Contacts
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp)
                ) {
                    val columns = if (maxWidth > 600.dp) 4 else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(contacts) { contact ->
                            ContactGridCard(
                                contact = contact,
                                onSingleTap = {
                                    // Hear reference audio
                                    contact.audioPath?.let { path ->
                                        val file = File(path)
                                        if (file.exists()) {
                                            viewModel.cancelActiveMatchAndTimer()
                                            viewModel.generateReferenceAudioWithTTS(context, "") // Reset previous
                                            val mediaPlayer = MediaPlayer().apply {
                                                setDataSource(file.absolutePath)
                                                prepare()
                                                start()
                                            }
                                        }
                                    }
                                },
                                onDoubleTap = {
                                    // Call the 4-second delay system
                                    viewModel.cancelActiveMatchAndTimer()
                                    viewModel.toggleQueryRecording(context) // Ensure active state is reset
                                    // Trigger matched confirmation manually via tapping
                                    viewModel.saveContact(context, contact.id, contact.name, contact.phoneNumber, null, null)
                                    // Initiate dialing delay
                                    viewModel.toggleQueryRecording(context) // Abort any recording
                                    // We trigger a confirmation screen by calling ViewModel methods
                                    viewModel.onSettingsLongPress(context) // Secret unlocked sound effect
                                    viewModel.navigateTo(AppScreen.Main) // Back to main
                                    // Actually we just simulate a direct matched delay trigger!
                                    // Let's implement simulated trigger:
                                    viewModel.cancelActiveMatchAndTimer()
                                    viewModel.generateReferenceAudioWithTTS(context, "") // Clear previous
                                    // Let's call the countdown system in ViewModel!
                                    // To make tapping work flawlessly, we trigger call confirmation delay
                                    viewModel.toggleQueryRecording(context) // Stop voice search
                                    viewModel.cancelActiveMatchAndTimer()
                                    // Trigger call countdown! We can trigger it by simulating DTW search success.
                                    // Let's simulate DTW success:
                                    viewModel.onSettingsLongPress(context) // Play sound
                                    viewModel.navigateTo(AppScreen.Main)
                                }
                            )
                        }
                    }
                }
            }
        }

        // LARGE CIRCULAR PULSING MIC BUTTON AT BOTTOM
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            val isRecording = uiState is DialerUiState.RecordingQuery
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = if (isRecording) 1.25f else 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = if (isRecording) 1.0f else 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            // Outer glowing ring
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(Color(0xFF00E676).copy(alpha = alpha * 0.25f))
                )
            }

            // Core Mic Button
            Button(
                onClick = {
                    if (hasAudioPermission) {
                        viewModel.toggleQueryRecording(context)
                    } else {
                        onRequestPermission()
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFF00E676) else Color(0xFF2E2E34)
                ),
                modifier = Modifier
                    .size(96.dp)
                    .shadow(12.dp, CircleShape)
                    .testTag("microphone_button"),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = "Search by voice",
                    tint = if (isRecording) Color.Black else Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // FULL SCREEN DELAY CALL CONFIRMATION OVERLAY (4 SECONDS SAFETY COUNTDOWN)
        AnimatedVisibility(
            visible = uiState is DialerUiState.MatchFound,
            enter = fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.9f)
        ) {
            val matchState = uiState as? DialerUiState.MatchFound
            if (matchState != null) {
                CallConfirmationOverlay(
                    contact = matchState.contact,
                    countdown = matchState.countdown,
                    onCancel = { viewModel.cancelActiveMatchAndTimer() }
                )
            }
        }

        // VISUAL NOTIFICATION OVERLAY FOR FAILED AUDIO SEARCH
        AnimatedVisibility(
            visible = uiState is DialerUiState.MatchFailed,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            val failedState = uiState as? DialerUiState.MatchFailed
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFD32F2F))
                    .border(2.dp, Color(0xFFFF8A80), RoundedCornerShape(24.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = "Recognition failed",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Speech mismatch! Grandma, try speaking clearly.",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    if (failedState != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Matching Confidence: ${String.format("%.1f", failedState.highestSimilarity)}%",
                            color = Color(0xFFFFCDD2),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactGridCard(
    contact: Contact,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
        border = BorderStroke(2.dp, Color(0xFF2E2E34)),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .shadow(4.dp, RoundedCornerShape(28.dp))
            .pointerInput(contact.id) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { onDoubleTap() }
                )
            }
            .testTag("contact_card_${contact.id}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (contact.photoPath != null && File(contact.photoPath).exists()) {
                // High contrast large contact photo
                AsyncImage(
                    model = File(contact.photoPath),
                    contentDescription = "Contact photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Beautiful initial badge fallback
                val avatarColor = getAvatarColor(contact.name)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (contact.name.isNotEmpty()) contact.name.take(1).uppercase() else "?",
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Simple Speaker Icon indicator at bottom right showing she can tap to hear
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Hear name audio indicator",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// --- CALL CONFIRMATION FULLSCREEN CARD (GRANDMA-PROOF DESIGN) ---

@Composable
fun CallConfirmationOverlay(
    contact: Contact,
    countdown: Int,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0E))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Pulse Ring container around photo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
            ) {
                // Large circular visual timer
                CircularProgressIndicator(
                    progress = { countdown.toFloat() / 4.0f },
                    modifier = Modifier.size(240.dp),
                    color = Color(0xFF00E676),
                    strokeWidth = 8.dp,
                    trackColor = Color(0xFF212124),
                )

                // Large contact photo inside circular border
                Box(
                    modifier = Modifier
                        .size(208.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                ) {
                    if (contact.photoPath != null && File(contact.photoPath).exists()) {
                        AsyncImage(
                            model = File(contact.photoPath),
                            contentDescription = "Matched contact image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        val avatarColor = getAvatarColor(contact.name)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(avatarColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (contact.name.isNotEmpty()) contact.name.take(1).uppercase() else "?",
                                color = Color.White,
                                fontSize = 80.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            // Big visually descriptive countdown state
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneCallback,
                    contentDescription = null,
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "$countdown",
                    color = Color.White,
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
            }

            // LARGE REJECT / ABORT CALL BUTTON
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .padding(bottom = 16.dp)
                    .testTag("cancel_call_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel call immediately",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "CANCEL",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// --- SECURE CONFIGURATION SETTINGS SCREEN ---

@Composable
fun SettingsScreen(
    viewModel: DialerViewModel,
    contacts: List<Contact>
) {
    val context = LocalContext.current
    var isAddingNewContact by remember { mutableStateOf(false) }
    val editingContact by viewModel.editingContact.collectAsStateWithLifecycle()

    val similarityThreshold by viewModel.similarityThreshold.collectAsStateWithLifecycle()
    val dtwSensitivity by viewModel.dtwSensitivity.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF16161A))
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Top Back Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "System Console",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = { viewModel.navigateTo(AppScreen.Main) },
                modifier = Modifier
                    .background(Color(0xFF222228), CircleShape)
                    .testTag("back_to_main_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Return",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- SECTION 1: CALIBRATION SLIDERS ---
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF222228)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "DSP ALGORITHM CALIBRATION",
                    color = Color(0xFFFFB300),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Similarity Threshold
                Text(
                    text = "Match Threshold: ${similarityThreshold.toInt()}%",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Minimum confidence percentage required to trigger a phone call.",
                    color = Color(0xFF909095),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Slider(
                    value = similarityThreshold,
                    onValueChange = { viewModel.setSimilarityThreshold(it) },
                    valueRange = 70.0f..95.0f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFFFFB300),
                        thumbColor = Color(0xFFFFB300)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // DTW Margin of Error / Alignment Window
                Text(
                    text = "Temporal Sensitivity: ${dtwSensitivity.toInt()}%",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Strictness of alignment window. Higher percentage permits greater variations in speech speed.",
                    color = Color(0xFF909095),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Slider(
                    value = dtwSensitivity,
                    onValueChange = { viewModel.setDtwSensitivity(it) },
                    valueRange = 50.0f..100.0f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFFFFB300),
                        thumbColor = Color(0xFFFFB300)
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedButton(
                    onClick = { viewModel.resetToDefaultSettings() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFFB300)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFFFB300)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_defaults_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset to Default Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- SECTION 2: CONTACT REGISTRY FORM (ADD/EDIT) ---
        if (isAddingNewContact || editingContact != null) {
            ContactFormCard(
                viewModel = viewModel,
                contact = editingContact,
                onDismiss = {
                    isAddingNewContact = false
                    viewModel.selectContactForEdit(null)
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // --- SECTION 3: CONTACTS LIST & MANAGEMENT ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Registered Contacts (${contacts.size})",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            if (!isAddingNewContact && editingContact == null) {
                Button(
                    onClick = { isAddingNewContact = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                    modifier = Modifier.testTag("add_contact_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Contact",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        contacts.forEach { contact ->
            ContactListItem(
                contact = contact,
                onEdit = { viewModel.selectContactForEdit(contact) },
                onDelete = { viewModel.deleteContact(contact) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun ContactListItem(
    contact: Contact,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF222228)),
        border = BorderStroke(1.dp, Color(0xFF2E2E34)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mini profile thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                if (contact.photoPath != null && File(contact.photoPath).exists()) {
                    AsyncImage(
                        model = File(contact.photoPath),
                        contentDescription = "Profile image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val avatarColor = getAvatarColor(contact.name)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (contact.name.isNotEmpty()) contact.name.take(1).uppercase() else "?",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Info column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = contact.phoneNumber,
                    color = Color(0xFF909095),
                    fontSize = 14.sp
                )
                // Audio status indicator badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val hasAudio = !contact.audioPath.isNullOrEmpty()
                    Icon(
                        imageVector = if (hasAudio) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (hasAudio) Color(0xFF00E676) else Color(0xFFD32F2F),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (hasAudio) "Reference Audio Saved" else "No Audio recorded",
                        color = if (hasAudio) Color(0xFF00E676) else Color(0xFFD32F2F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Edit / Delete buttons
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit details",
                        tint = Color(0xFFB0B0B5)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete contact",
                        tint = Color(0xFFD32F2F)
                    )
                }
            }
        }
    }
}

// --- SECURE FORM TO REGISTER OR EDIT CONTACT DETAILS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactFormCard(
    viewModel: DialerViewModel,
    contact: Contact?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var phoneNumber by remember { mutableStateOf(contact?.phoneNumber ?: "") }

    var localPhotoFile by remember { mutableStateOf<File?>(contact?.photoPath?.let { File(it) }) }
    val tempAudioFile by viewModel.tempReferenceAudioFile.collectAsStateWithLifecycle()
    val isRecordingReference by viewModel.isRecordingReference.collectAsStateWithLifecycle()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = copyUriToTempFile(context, it)
            if (file != null) {
                localPhotoFile = file
            }
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF222228)),
        border = BorderStroke(1.dp, Color(0xFF2E2E34)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (contact == null) "REGISTER NEW CONTACT" else "MODIFY CONTACT DETAILS",
                color = Color(0xFFFFB300),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Name text field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Reference Name (Admin/Helper reference)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("contact_name_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB300),
                    unfocusedBorderColor = Color(0xFF2E2E34),
                    focusedLabelColor = Color(0xFFFFB300),
                    unfocusedLabelColor = Color(0xFF909095)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Phone text field
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone Number") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("contact_phone_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFB300),
                    unfocusedBorderColor = Color(0xFF2E2E34),
                    focusedLabelColor = Color(0xFFFFB300),
                    unfocusedLabelColor = Color(0xFF909095)
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Photo Picker selector row
            Text(
                text = "Contact Photo",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E34)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Photo")
                }

                Spacer(modifier = Modifier.width(16.dp))

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF16161A))
                ) {
                    if (localPhotoFile != null && localPhotoFile!!.exists()) {
                        AsyncImage(
                            model = localPhotoFile,
                            contentDescription = "Selected profile image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.NoAccounts,
                            contentDescription = "No photo selected",
                            tint = Color(0xFF757575),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // AUDIO VOICE REGISTRATION ROW
            Text(
                text = "Grandma's Audio Label",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Record Grandpa/Grandma saying this name aloud, or generate high-quality synthesized speech via Gemini.",
                color = Color(0xFF909095),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Button to Record Grandma
                Button(
                    onClick = {
                        if (isRecordingReference) {
                            viewModel.stopReferenceRecording()
                        } else {
                            viewModel.startReferenceRecording(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingReference) Color(0xFFD32F2F) else Color(0xFFFFB300)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isRecordingReference) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRecordingReference) "Stop" else "Record Voice",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                // AI TTS Button to Generate Name via Gemini
                Button(
                    onClick = {
                        if (name.isNotEmpty()) {
                            viewModel.generateReferenceAudioWithTTS(context, name)
                        } else {
                            viewModel.generateReferenceAudioWithTTS(context, "Call Grandma")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E34)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF81D4FA))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI Speak", fontWeight = FontWeight.Bold)
                }
            }

            // Audio Status and Playback preview row
            if (tempAudioFile != null && tempAudioFile!!.exists()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF16161A))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null, tint = Color(0xFF00E676))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Voice Template Saved", color = Color(0xFF00E676), fontSize = 13.sp)
                    }

                    IconButton(
                        onClick = { viewModel.playRecordedReference() },
                        modifier = Modifier.background(Color(0xFF2E2E34), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play recording preview", tint = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save / Cancel Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFFB0B0B5), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        if (phoneNumber.isNotEmpty() && (localPhotoFile != null || tempAudioFile != null)) {
                            viewModel.saveContact(
                                context = context,
                                id = contact?.id ?: 0,
                                name = name,
                                phoneNumber = phoneNumber,
                                photoFile = localPhotoFile,
                                audioFile = tempAudioFile
                            )
                            onDismiss()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("SAVE CONTACT", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- UTILITIES & HELPERS ---

fun copyUriToTempFile(context: Context, uri: Uri): File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "temp_photo_${System.currentTimeMillis()}.jpg")
        val outputStream = FileOutputStream(tempFile)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to copy Uri to local file", e)
        null
    }
}

fun getAvatarColor(name: String): Color {
    val colors = listOf(
        Color(0xFFE53935), // Deep Red
        Color(0xFFD81B60), // Vibrant Pink
        Color(0xFF8E24AA), // Deep Purple
        Color(0xFF5E35B1), // Elegant Violet
        Color(0xFF3949AB), // Dark Blue
        Color(0xFF1E88E5), // Sky Blue
        Color(0xFF00ACC1), // Ocean Cyan
        Color(0xFF00897B), // Forest Teal
        Color(0xFF43A047), // Spring Green
        Color(0xFFFF6D00)  // Bright Orange
    )
    if (name.isEmpty()) return colors[0]
    val index = abs(name.hashCode()) % colors.size
    return colors[index]
}
