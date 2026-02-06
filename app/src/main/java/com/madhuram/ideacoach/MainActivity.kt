package com.madhuram.ideacoach

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.madhuram.ideacoach.audio.AudioPlayer
import com.madhuram.ideacoach.audio.AudioRecorder
import com.madhuram.ideacoach.data.IdeaAnswerEntity
import com.madhuram.ideacoach.data.IdeaDatabase
import com.madhuram.ideacoach.data.IdeaRepository
import com.madhuram.ideacoach.data.IdeaStatus
import com.madhuram.ideacoach.data.SettingsRepository
import com.madhuram.ideacoach.data.TranscriptionStatus
import com.madhuram.ideacoach.model.Idea
import com.madhuram.ideacoach.model.toEntity
import com.madhuram.ideacoach.model.toModel
import com.madhuram.ideacoach.transcription.VoskTranscriber
import com.madhuram.ideacoach.ui.theme.IdeaCoachTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IdeaCoachTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IdeaCoachApp()
                }
            }
        }
    }
}

private val defaultQuestions = listOf(
    "What problem does this solve?",
    "Who benefits most?",
    "What is the smallest test you can run in 1 week?",
    "What could make this fail?",
    "What resources or people do you need?"
)

private const val DEFAULT_WAVEFORM_CACHE_LIMIT = 40
private const val COPIED_BANNER_DURATION_MS = 1200L

private enum class MicAction {
    NONE,
    RECORD,
    DICTATE
}

private class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = IdeaRepository(IdeaDatabase.getInstance(application).ideaDao())
    private val settingsRepository = SettingsRepository(application)
    private val transcriber = VoskTranscriber(application)

    private val ideasFlow = repository.observeIdeasWithAnswerCount().map { entities ->
        entities.map { it.toModel() }
    }

    val ideas = ideasFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    val questionDepth = settingsRepository.questionDepth.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        3
    )

    val waveformCacheLimit = settingsRepository.waveformCacheLimit.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DEFAULT_WAVEFORM_CACHE_LIMIT
    )

    fun observeIdea(id: String): Flow<Idea?> {
        return repository.observeIdea(id).map { it?.toModel() }
    }

    fun observeAnswers(id: String): Flow<Map<String, String>> {
        return repository.observeAnswers(id).map { answers ->
            answers.associate { it.question to it.answer }
        }
    }

    fun addIdea(rawText: String, audioPath: String?): String {
        val normalized = rawText.trim().replace(Regex("\\s+"), " ")
        val summary = summarize(normalized)
        val tag = inferTag(normalized)
        val transcriptionStatus = if (audioPath != null) {
            TranscriptionStatus.PENDING
        } else {
            TranscriptionStatus.DONE
        }
        val idea = Idea(
            id = UUID.randomUUID().toString(),
            rawText = normalized,
            summary = summary,
            tag = tag,
            nextStep = "Write a 3-bullet outline and a 1-week test.",
            risk = "Unclear user need or success metric.",
            audioPath = audioPath,
            status = IdeaStatus.NEW,
            transcriptionStatus = transcriptionStatus,
            lastTranscriptionError = null,
            createdAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            repository.insertIdea(idea.toEntity())
            if (audioPath != null) {
                transcribeAudio(idea.id, audioPath)
            }
        }
        return idea.id
    }

    fun setQuestionDepth(value: Int) {
        viewModelScope.launch {
            settingsRepository.setQuestionDepth(value)
        }
    }

    fun setWaveformCacheLimit(value: Int) {
        viewModelScope.launch {
            settingsRepository.setWaveformCacheLimit(value)
        }
    }

    fun updateStatus(id: String, status: IdeaStatus) {
        viewModelScope.launch {
            repository.updateStatus(id, status)
        }
    }

    fun retryTranscription(idea: Idea) {
        val path = idea.audioPath ?: return
        viewModelScope.launch {
            repository.updateTranscriptionStatus(idea.id, TranscriptionStatus.PENDING, null)
            transcribeAudio(idea.id, path)
        }
    }

    private fun transcribeAudio(ideaId: String, audioPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = transcriber.transcribe(File(audioPath))
            val text = result.getOrNull()?.trim().orEmpty()
            if (result.isSuccess && text.isNotBlank()) {
                repository.updateIdeaTextAndStatus(
                    id = ideaId,
                    rawText = text,
                    summary = summarize(text),
                    tag = inferTag(text),
                    status = TranscriptionStatus.DONE,
                    error = null
                )
            } else {
                val error = if (result.isSuccess) {
                    "No speech detected"
                } else {
                    result.exceptionOrNull()?.message ?: "Transcription failed"
                }
                repository.updateTranscriptionStatus(ideaId, TranscriptionStatus.FAILED, error)
            }
        }
    }

    fun saveAnswer(ideaId: String, question: String, answer: String) {
        viewModelScope.launch {
            repository.upsertAnswer(
                IdeaAnswerEntity(
                    ideaId = ideaId,
                    question = question,
                    answer = answer,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun buildAllMarkdown(filter: IdeaStatus?): String = withContext(Dispatchers.IO) {
        val ideas = repository.getIdeas()
            .map { it.toModel() }
            .let { list -> if (filter == null) list else list.filter { it.status == filter } }
        val answers = repository.getAllAnswers()
        val answersByIdea = answers.groupBy { it.ideaId }
        val builder = StringBuilder()
        builder.append("# IdeaCoach Export\n\n")
        ideas.forEachIndexed { index, idea ->
            val answerMap = answersByIdea[idea.id]
                ?.associate { it.question to it.answer }
                ?: emptyMap()
            builder.append(buildIdeaSection(idea, answerMap))
            if (index != ideas.lastIndex) {
                builder.append("\n\n---\n\n")
            }
        }
        builder.toString()
    }

    suspend fun buildSelectedMarkdown(ids: Set<String>): String = withContext(Dispatchers.IO) {
        val ideas = repository.getIdeas()
            .map { it.toModel() }
            .filter { ids.contains(it.id) }
        val answers = repository.getAllAnswers()
        val answersByIdea = answers.groupBy { it.ideaId }
        val builder = StringBuilder()
        builder.append("# IdeaCoach Export\n\n")
        ideas.forEachIndexed { index, idea ->
            val answerMap = answersByIdea[idea.id]
                ?.associate { it.question to it.answer }
                ?: emptyMap()
            builder.append(buildIdeaSection(idea, answerMap))
            if (index != ideas.lastIndex) {
                builder.append("\n\n---\n\n")
            }
        }
        builder.toString()
    }

    private fun summarize(text: String): String {
        if (text.isBlank()) return "(empty)"
        return if (text.length <= 120) text else text.take(117) + "..."
    }

    private fun inferTag(text: String): String {
        val lower = text.lowercase()
        return when {
            listOf("startup", "business", "market", "customer").any { it in lower } -> "Business"
            listOf("fitness", "health", "diet").any { it in lower } -> "Personal"
            listOf("app", "code", "build", "feature").any { it in lower } -> "Product"
            else -> "Idea"
        }
    }
}

@Composable
private fun IdeaCoachApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val ideas by viewModel.ideas.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                ideas = ideas,
                onCapture = { navController.navigate("capture") },
                onReview = { ideaId -> navController.navigate("review/$ideaId") },
                onExportAll = { status -> viewModel.buildAllMarkdown(status) },
                onExportSelected = { ids -> viewModel.buildSelectedMarkdown(ids) },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("capture") {
            CaptureScreen(
                onBack = { navController.popBackStack() },
                onSave = { text, audioPath ->
                    val id = viewModel.addIdea(text, audioPath)
                    navController.navigate("review/$id") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(
            route = "review/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            val idea by viewModel.observeIdea(id).collectAsStateWithLifecycle(initialValue = null)
            val answers by viewModel.observeAnswers(id).collectAsStateWithLifecycle(initialValue = emptyMap())
            val questionDepth by viewModel.questionDepth.collectAsStateWithLifecycle()
            val waveformCacheLimit by viewModel.waveformCacheLimit.collectAsStateWithLifecycle()

            if (idea == null) {
                LoadingScreen()
                return@composable
            }

            ReviewScreen(
                idea = idea!!,
                questionDepth = questionDepth,
                waveformCacheLimit = waveformCacheLimit,
                answers = answers,
                onAnswerChange = { question, answer ->
                    viewModel.saveAnswer(id, question, answer)
                },
                onDepthChange = viewModel::setQuestionDepth,
                onMarkReviewed = { viewModel.updateStatus(id, IdeaStatus.REVIEWED) },
                onRetryTranscription = { viewModel.retryTranscription(idea!!) },
                onDone = {
                    viewModel.updateStatus(id, IdeaStatus.DONE)
                    navController.popBackStack("home", false)
                }
            )
        }
        composable("settings") {
            val waveformCacheLimit by viewModel.waveformCacheLimit.collectAsStateWithLifecycle()
            SettingsScreen(
                waveformCacheLimit = waveformCacheLimit,
                onWaveformCacheLimitChange = viewModel::setWaveformCacheLimit,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AppTopBar(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (navigation != null) {
                navigation()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val shape = RoundedCornerShape(18.dp)

    Surface(
        color = background,
        contentColor = contentColor,
        shape = shape,
        border = border,
        modifier = Modifier
            .defaultMinSize(minHeight = 32.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InfoPill(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CountBadge(count: Int) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = shape
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .defaultMinSize(minWidth = 18.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun SettingsScreen(
    waveformCacheLimit: Int,
    onWaveformCacheLimitChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    var sliderValue by remember(waveformCacheLimit) { mutableStateOf(waveformCacheLimit.toFloat()) }
    val minValue = 10f
    val maxValue = 200f
    val context = androidx.compose.ui.platform.LocalContext.current
    var cacheSizeMb by remember { mutableStateOf(0f) }

    LaunchedEffect(waveformCacheLimit) {
        cacheSizeMb = getWaveformCacheSizeMb(context)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Settings",
                navigation = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Waveform cache limit", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Keep up to ${sliderValue.roundToInt()} cached waveforms for faster playback.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format(Locale.getDefault(), "%.1f MB", cacheSizeMb),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Slider(
                value = sliderValue.coerceIn(minValue, maxValue),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    onWaveformCacheLimitChange(sliderValue.roundToInt())
                },
                valueRange = minValue..maxValue,
                steps = 18,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Lower values use less storage. Higher values reduce re-scanning.",
                style = MaterialTheme.typography.labelMedium
            )
            Divider()
            Button(onClick = {
                val cleared = clearWaveformCache(context)
                cacheSizeMb = getWaveformCacheSizeMb(context)
                Toast.makeText(context, "Cleared $cleared cached waveforms", Toast.LENGTH_SHORT).show()
            }) {
                Text("Clear cache now")
            }
            OutlinedButton(onClick = {
                val cleared = clearWaveformCache(context)
                sliderValue = DEFAULT_WAVEFORM_CACHE_LIMIT.toFloat()
                onWaveformCacheLimitChange(DEFAULT_WAVEFORM_CACHE_LIMIT)
                cacheSizeMb = getWaveformCacheSizeMb(context)
                Toast.makeText(
                    context,
                    "Cleared $cleared cached waveforms and reset limit",
                    Toast.LENGTH_SHORT
                ).show()
            }) {
                Text("Clear cache & reset limit")
            }
        }
    }
}

@Composable
private fun HomeScreen(
    ideas: List<Idea>,
    onCapture: () -> Unit,
    onReview: (String) -> Unit,
    onExportAll: suspend (IdeaStatus?) -> String,
    onExportSelected: suspend (Set<String>) -> String,
    onOpenSettings: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf<IdeaStatus?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val audioPlayer = remember { AudioPlayer() }
    var playingId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.stop()
        }
    }

    LaunchedEffect(selectionMode) {
        if (!selectionMode) {
            selectedIds.clear()
        }
    }

    val filteredIdeas = when (filter) {
        null -> ideas
        else -> ideas.filter { it.status == filter }
    }

    val allFilteredSelected = filteredIdeas.isNotEmpty() &&
        filteredIdeas.all { selectedIds.contains(it.id) }

    val allCount = ideas.size
    val newCount = ideas.count { it.status == IdeaStatus.NEW }
    val reviewedCount = ideas.count { it.status == IdeaStatus.REVIEWED }
    val doneCount = ideas.count { it.status == IdeaStatus.DONE }

    Scaffold(
        topBar = {
            val filterValue = filter
            val exportLabel = when {
                selectionMode -> "Export (${selectedIds.size})"
                filterValue == null -> "Export all"
                else -> "Export ${filterValue.label}"
            }
            val exportEnabled = if (selectionMode) {
                selectedIds.isNotEmpty()
            } else {
                filteredIdeas.isNotEmpty()
            }
            AppTopBar(
                title = "IdeaCoach",
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                if (allFilteredSelected) {
                                    selectedIds.removeAll(filteredIdeas.map { it.id }.toSet())
                                } else {
                                    filteredIdeas.forEach { idea ->
                                        if (!selectedIds.contains(idea.id)) {
                                            selectedIds.add(idea.id)
                                        }
                                    }
                                }
                            },
                            enabled = filteredIdeas.isNotEmpty()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (allFilteredSelected) {
                                        "Deselect all"
                                    } else {
                                        "Select all filtered"
                                    }
                                )
                                if (filteredIdeas.isNotEmpty()) {
                                    CountBadge(filteredIdeas.size)
                                }
                            }
                        }
                        TextButton(
                            onClick = { selectedIds.clear() },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Text("Select none")
                        }
                    }
                    TextButton(
                        onClick = {
                            selectionMode = !selectionMode
                        },
                        enabled = true
                    ) {
                        Text(if (selectionMode) "Cancel" else "Select")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val markdown = if (selectionMode) {
                                    onExportSelected(selectedIds.toSet())
                                } else {
                                    onExportAll(filter)
                                }
                                val base = if (selectionMode) {
                                    "selected-ideas"
                                } else if (filterValue == null) {
                                    "all-ideas"
                                } else {
                                    "${filterValue?.label?.lowercase(Locale.getDefault()) ?: "ideas"}-ideas"
                                }
                                val fileName = makeExportFileName(base)
                                val message = saveMarkdownToDownloads(context, fileName, markdown)
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                if (selectionMode) {
                                    selectionMode = false
                                }
                            }
                        },
                        enabled = exportEnabled
                    ) {
                        Text(exportLabel)
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCapture) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Capture first. Coach later.",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            FilterRow(
                filter = filter,
                onFilterChange = {
                    filter = it
                    if (selectionMode) {
                        selectionMode = false
                    }
                },
                allCount = allCount,
                newCount = newCount,
                reviewedCount = reviewedCount,
                doneCount = doneCount
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (filteredIdeas.isEmpty()) {
                EmptyState(onCapture, filter)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredIdeas) { idea ->
                        IdeaCard(
                            idea = idea,
                            isPlaying = playingId == idea.id,
                            selectable = selectionMode,
                            selected = selectedIds.contains(idea.id),
                            onToggleSelect = {
                                if (selectedIds.contains(idea.id)) {
                                    selectedIds.remove(idea.id)
                                } else {
                                    selectedIds.add(idea.id)
                                }
                            },
                            onToggleAudio = { path ->
                                audioPlayer.toggle(path) { playing ->
                                    playingId = if (playing) idea.id else null
                                }
                            },
                            onReview = { onReview(idea.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: IdeaStatus?,
    onFilterChange: (IdeaStatus?) -> Unit,
    allCount: Int,
    newCount: Int,
    reviewedCount: Int,
    doneCount: Int
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPill(
            label = "All ($allCount)",
            selected = filter == null,
            onClick = { onFilterChange(null) }
        )
        FilterPill(
            label = "New ($newCount)",
            selected = filter == IdeaStatus.NEW,
            onClick = { onFilterChange(IdeaStatus.NEW) }
        )
        FilterPill(
            label = "Reviewed ($reviewedCount)",
            selected = filter == IdeaStatus.REVIEWED,
            onClick = { onFilterChange(IdeaStatus.REVIEWED) }
        )
        FilterPill(
            label = "Done ($doneCount)",
            selected = filter == IdeaStatus.DONE,
            onClick = { onFilterChange(IdeaStatus.DONE) }
        )
    }
}

@Composable
private fun EmptyState(onCapture: () -> Unit, filter: IdeaStatus?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val message = when (filter) {
            IdeaStatus.NEW -> "No new ideas"
            IdeaStatus.REVIEWED -> "No reviewed ideas"
            IdeaStatus.DONE -> "No done ideas"
            null -> "No ideas yet"
        }
        Text(message, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Tap + to capture a new idea.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCapture) {
            Text("Capture your first idea")
        }
    }
}

@Composable
private fun IdeaCard(
    idea: Idea,
    isPlaying: Boolean,
    selectable: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleAudio: (String) -> Unit,
    onReview: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectable) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                }
                Text(
                    text = idea.summary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(idea.tag)
                InfoPill(idea.status.label)
                if (idea.answerCount > 0) {
                    InfoPill("Answers ${idea.answerCount}")
                }
            }
            if (idea.audioPath != null) {
                val transcriptionColor = when (idea.transcriptionStatus) {
                    TranscriptionStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                    TranscriptionStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    TranscriptionStatus.FAILED -> MaterialTheme.colorScheme.error
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice note attached", style = MaterialTheme.typography.labelMedium)
                    Text(if (isPlaying) "Playing" else "Paused", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Transcription: ${idea.transcriptionStatus.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = transcriptionColor
                )
                if (!idea.lastTranscriptionError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Error: ${idea.lastTranscriptionError}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { onToggleAudio(idea.audioPath) }) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onReview) {
                Text("Review")
            }
        }
    }
}

@Composable
private fun CaptureScreen(onBack: () -> Unit, onSave: (String, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = remember { AudioRecorder(context) }
    var text by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(0L) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf(MicAction.NONE) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val dictationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val transcript = results?.firstOrNull()?.trim()
            if (!transcript.isNullOrBlank()) {
                text = transcript
                permissionError = null
            } else {
                permissionError = "No speech detected."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            when (pendingAction) {
                MicAction.RECORD -> {
                    startRecording(recorder, setRecording = {
                        isRecording = it
                        if (it) {
                            startTime = SystemClock.elapsedRealtime()
                        }
                    }, setError = { permissionError = it })
                }
                MicAction.DICTATE -> {
                    launchDictation(context, dictationLauncher, setError = { permissionError = it })
                }
                MicAction.NONE -> Unit
            }
        } else {
            permissionError = "Microphone permission denied."
        }
        pendingAction = MicAction.NONE
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Capture",
                navigation = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Quick capture. No questions yet.", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Idea") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSave(text, null) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save idea")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Voice capture", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (permissionError != null) {
                Text(permissionError ?: "", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            val recordButtonLabel = if (isRecording) "Stop recording" else "Start recording"
            val recordButtonColor = if (isRecording) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ) else ButtonDefaults.buttonColors()

            Button(
                onClick = {
                    if (isRecording) {
                        val durationSeconds = ((SystemClock.elapsedRealtime() - startTime) / 1000)
                            .coerceAtLeast(1)
                        val audioFile = recorder.stop()
                        isRecording = false
                        if (audioFile != null) {
                            val stub = "Voice note captured (${durationSeconds}s). Transcribing offline..."
                            onSave(stub, audioFile.absolutePath)
                        } else {
                            permissionError = "Recording failed."
                        }
                    } else {
                        permissionError = null
                        if (hasPermission) {
                            startRecording(recorder, setRecording = {
                                isRecording = it
                                if (it) {
                                    startTime = SystemClock.elapsedRealtime()
                                }
                            }, setError = { permissionError = it })
                        } else {
                            pendingAction = MicAction.RECORD
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = recordButtonColor
            ) {
                Text(recordButtonLabel)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Voice notes are saved for playback and offline transcription (model required).",
                style = MaterialTheme.typography.labelMedium
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    permissionError = null
                    if (hasPermission) {
                        launchDictation(context, dictationLauncher, setError = { permissionError = it })
                    } else {
                        pendingAction = MicAction.DICTATE
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !isRecording,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dictate (transcribe)")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Dictation uses the system speech engine (offline if available).",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

private fun startRecording(
    recorder: AudioRecorder,
    setRecording: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    try {
        recorder.start()
        setRecording(true)
    } catch (_: Exception) {
        setError("Unable to start recording.")
        setRecording(false)
    }
}

private fun launchDictation(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    setError: (String?) -> Unit
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your idea")
    }
    val canHandle = intent.resolveActivity(context.packageManager) != null
    if (canHandle) {
        launcher.launch(intent)
    } else {
        setError("Speech recognition not available on this device.")
    }
}

@Composable
private fun ReviewScreen(
    idea: Idea,
    questionDepth: Int,
    waveformCacheLimit: Int,
    answers: Map<String, String>,
    onAnswerChange: (String, String) -> Unit,
    onDepthChange: (Int) -> Unit,
    onMarkReviewed: () -> Unit,
    onRetryTranscription: () -> Unit,
    onDone: () -> Unit
) {
    val questions = defaultQuestions.take(questionDepth.coerceIn(1, defaultQuestions.size))
    val canMarkReviewed = idea.status == IdeaStatus.NEW
    val canDone = idea.status != IdeaStatus.DONE
    val audioPlayer = remember { AudioPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var isSeeking by remember { mutableStateOf(false) }
    var waveform by remember(idea.audioPath) { mutableStateOf<List<Float>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val markdown = remember(idea, answers) { buildMarkdown(idea, answers) }
    val scope = rememberCoroutineScope()
    var copySignal by remember { mutableStateOf(0) }
    var showCopied by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.stop()
        }
    }

    LaunchedEffect(idea.audioPath) {
        val path = idea.audioPath
        waveform = if (path != null) {
            loadWaveform(context, path, keepLast = waveformCacheLimit)
        } else {
            emptyList()
        }
        durationMs = 0
        positionMs = 0
    }

    LaunchedEffect(isPlaying, idea.audioPath, isSeeking) {
        val path = idea.audioPath ?: return@LaunchedEffect
        while (isPlaying) {
            durationMs = audioPlayer.getDuration()
            if (!isSeeking) {
                positionMs = audioPlayer.getCurrentPosition()
            }
            kotlinx.coroutines.delay(250)
        }
        if (!isSeeking) {
            positionMs = audioPlayer.getCurrentPosition()
        }
    }

    LaunchedEffect(copySignal) {
        if (copySignal == 0) return@LaunchedEffect
        showCopied = true
        kotlinx.coroutines.delay(COPIED_BANNER_DURATION_MS)
        showCopied = false
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Review",
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val fileName = makeExportFileName(idea.summary)
                                val message = saveMarkdownToDownloads(context, fileName, markdown)
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text("Export")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canMarkReviewed) {
                    OutlinedButton(onClick = onMarkReviewed) {
                        Text("Mark reviewed")
                    }
                }
                Button(onClick = onDone, enabled = canDone) {
                    Text("Done")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(idea.summary, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill(idea.tag)
                    InfoPill(idea.status.label)
                }
                if (idea.audioPath != null) {
                    val transcriptionColor = when (idea.transcriptionStatus) {
                        TranscriptionStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                        TranscriptionStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                        TranscriptionStatus.FAILED -> MaterialTheme.colorScheme.error
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice note attached", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (isPlaying) "Playing" else "Paused",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            try {
                                audioPlayer.toggle(idea.audioPath, onStateChange = { isPlaying = it })
                            } catch (_: Exception) {
                                isPlaying = false
                            }
                        }
                    ) {
                        Text(if (isPlaying) "Pause audio" else "Play audio")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Transcription: ${idea.transcriptionStatus.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = transcriptionColor
                    )
                    if (!idea.lastTranscriptionError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    text = "Last transcription error",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                SelectionContainer {
                                    Text(
                                        text = idea.lastTranscriptionError ?: "",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        val errorText = idea.lastTranscriptionError ?: ""
                                        copyToClipboard(context, "Transcription error", errorText)
                                        copySignal += 1
                                    }
                                ) {
                                    Text("Copy error message")
                                }
                            }
                        }
                    }
                    if (idea.transcriptionStatus == TranscriptionStatus.FAILED) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(onClick = onRetryTranscription) {
                            Text("Retry transcription")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    WaveformView(
                        samples = waveform,
                        progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = positionMs.toFloat(),
                        valueRange = 0f..max(1f, durationMs.toFloat()),
                        onValueChange = { newValue ->
                            isSeeking = true
                            positionMs = newValue.toInt()
                        },
                        onValueChangeFinished = {
                            audioPlayer.seekTo(positionMs)
                            isSeeking = false
                        },
                        enabled = durationMs > 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall)
                        Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("Coach depth", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DepthButton(label = "Light", active = questionDepth == 1) { onDepthChange(1) }
                    DepthButton(label = "Normal", active = questionDepth == 3) { onDepthChange(3) }
                    DepthButton(label = "Deep", active = questionDepth == 5) { onDepthChange(5) }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Clarifying questions", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                questions.forEach { question ->
                    val initial = answers[question] ?: ""
                    var answer by remember(question, initial) { mutableStateOf(initial) }
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { newValue ->
                            if (newValue != answer) {
                                answer = newValue
                                onAnswerChange(question, newValue)
                            }
                        },
                        label = { Text(question) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                Text("Action snapshot", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Next step: ${idea.nextStep}")
                Spacer(modifier = Modifier.height(4.dp))
                Text("Risk: ${idea.risk}")
            }
            AnimatedVisibility(
                visible = showCopied,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(padding)
                    .padding(top = 8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp
                ) {
                    Text(
                        text = "Copied",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DepthButton(label: String, active: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.height(40.dp)
    if (active) {
        FilledTonalButton(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    }
}

private fun buildMarkdown(idea: Idea, answers: Map<String, String>): String {
    val builder = StringBuilder()
    builder.append("# IdeaCoach Export\n\n")
    builder.append(buildIdeaSection(idea, answers))
    return builder.toString()
}

private fun buildIdeaSection(idea: Idea, answers: Map<String, String>): String {
    val builder = StringBuilder()
    builder.append("## Summary\n")
    builder.append("- Summary: ").append(idea.summary).append("\n")
    builder.append("- Tag: ").append(idea.tag).append("\n")
    builder.append("- Status: ").append(idea.status.label).append("\n")
    builder.append("- Transcription: ").append(idea.transcriptionStatus.label).append("\n")
    if (!idea.lastTranscriptionError.isNullOrBlank()) {
        builder.append("- Transcription error: ").append(idea.lastTranscriptionError).append("\n")
    }
    builder.append("- Created: ").append(formatTimestamp(idea.createdAt)).append("\n\n")
    builder.append("## Raw Idea\n")
    builder.append(idea.rawText).append("\n\n")
    builder.append("## Action Snapshot\n")
    builder.append("- Next step: ").append(idea.nextStep).append("\n")
    builder.append("- Risk: ").append(idea.risk).append("\n\n")

    if (answers.isNotEmpty()) {
        builder.append("## Coach Answers\n")
        defaultQuestions.forEach { question ->
            val answer = answers[question]?.trim().orEmpty()
            if (answer.isNotBlank()) {
                builder.append("- **").append(question).append("**\n  ")
                builder.append(answer).append("\n")
            }
        }
    }
    return builder.toString()
}

private fun makeExportFileName(base: String): String {
    val safeBase = sanitizeFileName(base).ifBlank { "idea" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
    return "ideacoach-$safeBase-$stamp.md"
}

private fun sanitizeFileName(value: String): String {
    return value
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9-_]+"), "-")
        .trim('-')
        .take(32)
}

private suspend fun saveMarkdownToDownloads(
    context: Context,
    fileName: String,
    markdown: String
): String = withContext(Dispatchers.IO) {
    val bytes = markdown.toByteArray()
    val resolver = context.contentResolver

    return@withContext try {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val uri = resolver.insert(collection, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            }
            "Saved to Downloads: $fileName"
        } else {
            saveMarkdownToAppDownloads(context, fileName, bytes)
        }
    } catch (_: Exception) {
        saveMarkdownToAppDownloads(context, fileName, bytes)
    }
}

private fun saveMarkdownToAppDownloads(context: Context, fileName: String, bytes: ByteArray): String {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir
    val file = File(dir, fileName)
    return try {
        dir.mkdirs()
        file.writeBytes(bytes)
        "Saved to app storage: ${file.absolutePath}"
    } catch (_: Exception) {
        "Export failed"
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatDuration(durationMs: Int): String {
    val totalSeconds = max(0, durationMs / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

private suspend fun loadWaveform(
    context: Context,
    path: String,
    bars: Int = 64,
    keepLast: Int = DEFAULT_WAVEFORM_CACHE_LIMIT
): List<Float> = withContext(Dispatchers.IO) {
    val audioFile = File(path)
    if (!audioFile.exists()) return@withContext emptyList()

    val cacheDir = File(context.filesDir, "waveforms").apply { mkdirs() }
    val cacheKey = "${audioFile.name}-${audioFile.lastModified()}-$bars.bin"
    val cacheFile = File(cacheDir, cacheKey)

    if (cacheFile.exists()) {
        readWaveformCache(cacheFile)?.let { return@withContext it }
    }

    val totalBytes = audioFile.length().toInt()
    if (totalBytes <= 44) return@withContext emptyList()

    val dataBytes = totalBytes - 44
    val totalSamples = dataBytes / 2
    if (totalSamples <= 0) return@withContext emptyList()

    val samplesPerBar = max(1, totalSamples / bars)
    val amplitudes = mutableListOf<Float>()

    audioFile.inputStream().use { input ->
        input.skip(44)
        val buffer = ByteArray(samplesPerBar * 2)
        while (amplitudes.size < bars) {
            val read = input.read(buffer)
            if (read <= 0) break
            var maxAmp = 0
            var i = 0
            while (i + 1 < read) {
                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)
                val amplitude = abs(sample)
                if (amplitude > maxAmp) {
                    maxAmp = amplitude
                }
                i += 2
            }
            amplitudes.add(maxAmp / 32768f)
        }
    }

    while (amplitudes.size < bars) {
        amplitudes.add(0f)
    }

    writeWaveformCache(cacheFile, amplitudes, keepLast = keepLast)
    amplitudes
}

private fun readWaveformCache(file: File): List<Float>? {
    return try {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val count = input.readInt()
            if (count <= 0) return null
            val values = ArrayList<Float>(count)
            repeat(count) {
                values.add(input.readFloat())
            }
            values
        }
    } catch (_: Exception) {
        null
    }
}

private fun writeWaveformCache(file: File, samples: List<Float>, keepLast: Int) {
    try {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
            output.writeInt(samples.size)
            samples.forEach { output.writeFloat(it) }
        }
        file.parentFile?.let { pruneWaveformCache(it, keepLast = max(5, keepLast)) }
    } catch (_: Exception) {
    }
}

private fun pruneWaveformCache(dir: File, keepLast: Int) {
    val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
    files.drop(keepLast).forEach { file ->
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}

private fun clearWaveformCache(context: Context): Int {
    val dir = File(context.filesDir, "waveforms")
    val files = dir.listFiles() ?: return 0
    var deleted = 0
    files.forEach { file ->
        if (file.delete()) {
            deleted += 1
        }
    }
    return deleted
}

private fun getWaveformCacheSizeMb(context: Context): Float {
    val dir = File(context.filesDir, "waveforms")
    val files = dir.listFiles() ?: return 0f
    val bytes = files.sumOf { it.length() }
    return bytes / (1024f * 1024f)
}

@Composable
private fun WaveformView(samples: List<Float>, progress: Float) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val bars = if (samples.isNotEmpty()) samples else List(32) { 0.2f }
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        val barWidth = size.width / bars.size
        val centerY = size.height / 2
        bars.forEachIndexed { index, amp ->
            val barHeight = amp.coerceIn(0f, 1f) * size.height
            val x = index * barWidth + barWidth / 2
            val color = if (index.toFloat() / bars.size <= clampedProgress) {
                activeColor
            } else {
                inactiveColor
            }
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2),
                end = androidx.compose.ui.geometry.Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}
