package com.example.lect9backgroundwork

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.IOException

// =====================================================================
// 1. MODELS: DTOs (server JSON), our own model, and Room entities
// =====================================================================

// ---- DTOs: shaped exactly like the server's JSON ----

@Serializable
data class NoteDto(val userId: Int, val id: Int, val title: String, val body: String)

@Serializable
data class NewNoteDto(val userId: Int, val title: String, val body: String)

// ---- Our own model, used by the UI ----

data class Note(
    val localId: Int,
    val title: String,
    val body: String,
    val uploaded: Boolean,
    val fromServer: Boolean,
    val lat: Double?,
    val lng: Double?
)

// ---- Room entities ----

@Entity(tableName = "route_points")
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val time: Long
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val localId: Int = 0,
    val title: String,
    val body: String,
    val uploaded: Boolean,
    val fromServer: Boolean,
    val lat: Double?,
    val lng: Double?
)

// ---- Mapping between the three layers ----

fun NoteDto.toDomain() = Note(
    localId = 0, title = title, body = body,
    uploaded = true, fromServer = true, lat = null, lng = null
)

fun Note.toEntity() = NoteEntity(
    localId = localId, title = title, body = body,
    uploaded = uploaded, fromServer = fromServer, lat = lat, lng = lng
)

fun NoteEntity.toDomain() = Note(
    localId = localId, title = title, body = body,
    uploaded = uploaded, fromServer = fromServer, lat = lat, lng = lng
)

// =====================================================================
// 2. ROOM: the local source of truth
// =====================================================================

@Dao
interface RouteDao {
    @Insert
    suspend fun insert(point: RoutePointEntity)

    @Query("SELECT * FROM route_points ORDER BY id")
    fun observeAll(): Flow<List<RoutePointEntity>>

    @Query("DELETE FROM route_points")
    suspend fun clear()
}

@Dao
abstract class NoteDao {
    @Insert
    abstract suspend fun insert(note: NoteEntity)

    @Insert
    abstract suspend fun insertAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes ORDER BY localId DESC")
    abstract fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE uploaded = 0 AND fromServer = 0")
    abstract suspend fun getPending(): List<NoteEntity>

    @Query("UPDATE notes SET uploaded = 1 WHERE localId = :id")
    abstract suspend fun markUploaded(id: Int)

    @Query("DELETE FROM notes WHERE fromServer = 1")
    abstract suspend fun deleteServerNotes()

    /** Replaces the cached server notes in one transaction. Our own notes are untouched. */
    @Transaction
    open suspend fun replaceServerNotes(notes: List<NoteEntity>) {
        deleteServerNotes()
        insertAll(notes)
    }
}

@Database(entities = [RoutePointEntity::class, NoteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "courier.db"
                ).build().also { instance = it }
            }
    }
}

// =====================================================================
// 3. NETWORK: Retrofit + JSONPlaceholder
// =====================================================================

interface NotesApi {
    @GET("posts")
    suspend fun getNotes(@retrofit2.http.Query("userId") userId: Int): List<NoteDto>

    /** Deliberately wrong path, used by the "Break it: bad path" demo (the server answers 404). */
    @GET("postz")
    suspend fun getNotesBadPath(@retrofit2.http.Query("userId") userId: Int): List<NoteDto>

    @POST("posts")
    suspend fun createNote(@Body note: NewNoteDto): NoteDto
}

/** The three ways the Network demo can behave. */
enum class BreakMode { NORMAL, BAD_PATH, CLEARTEXT }

object NetworkModule {
    private val json = Json { ignoreUnknownKeys = true }   // survive new server fields

    private fun build(baseUrl: String): NotesApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NotesApi::class.java)

    val https: NotesApi by lazy { build("https://jsonplaceholder.typicode.com/") }

    /** Plain http: Android blocks this by default, which is the point of the demo. */
    val http: NotesApi by lazy { build("http://jsonplaceholder.typicode.com/") }
}

// =====================================================================
// 4. REPOSITORY: the front desk between the UI, Room and the server
// =====================================================================

private const val TAG = "NotesRepository"

/**
 * The UI and the Worker ask here, and never learn whether the data
 * came from Room or from the server.
 */
class NotesRepository(private val dao: NoteDao) {

    /** Saves a delivery note on the phone only. It is "waiting" until the Worker uploads it. */
    suspend fun addLocalNote(title: String, body: String, lat: Double?, lng: Double?) {
        dao.insert(
            Note(0, title, body, uploaded = false, fromServer = false, lat = lat, lng = lng).toEntity()
        )
    }

    /**
     * Downloads the notes for [userId] and caches them in Room (Room = source of truth).
     * [mode] lets the demo break the request on purpose.
     *
     * @return success with the notes, or failure with the cause (offline, 4xx/5xx, cleartext blocked)
     */
    suspend fun refreshNotes(userId: Int, mode: BreakMode): Result<List<Note>> =
        try {
            val api = if (mode == BreakMode.CLEARTEXT) NetworkModule.http else NetworkModule.https
            val dtos = if (mode == BreakMode.BAD_PATH) api.getNotesBadPath(userId) else api.getNotes(userId)
            val notes = dtos.map { it.toDomain() }
            dao.replaceServerNotes(notes.map { it.toEntity() })
            Log.d(TAG, "fetched ${notes.size} notes")
            Result.success(notes)
        } catch (e: CancellationException) {
            throw e                                   // let coroutines stop cleanly
        } catch (e: IOException) {
            Log.e(TAG, "network failure: ${e.message}")
            Result.failure(e)                         // offline, timeout, cleartext blocked
        } catch (e: HttpException) {
            Log.e(TAG, "server said HTTP ${e.code()}")
            Result.failure(e)                         // 4xx / 5xx
        }

    /**
     * Sends every waiting note to the server. Called by [SyncWorker].
     * Throws on failure, so the Worker can decide to retry.
     */
    suspend fun uploadPendingNotes() {
        val pending = dao.getPending()
        Log.d(TAG, "uploading ${pending.size} waiting notes")
        for (note in pending) {
            val created = NetworkModule.https.createNote(
                NewNoteDto(userId = 1, title = note.title, body = note.body)
            )
            Log.d(TAG, "server accepted note (fake id ${created.id})")
            dao.markUploaded(note.localId)
        }
    }
}

/** Creates one repository for the whole app, usable from the UI and from the Worker. */
object ServiceLocator {
    @Volatile
    private var repository: NotesRepository? = null

    fun notesRepository(context: Context): NotesRepository =
        repository ?: synchronized(this) {
            repository ?: NotesRepository(AppDatabase.get(context).noteDao()).also { repository = it }
        }
}

// =====================================================================
// 5. WORKMANAGER: the job and its conditions
// =====================================================================

/** Step 1: the job. WorkManager runs this when the conditions are met. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository = ServiceLocator.notesRepository(appContext)

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "attempt $runAttemptCount")
        return try {
            repository.uploadPendingNotes()
            Result.success()                // done
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Step 2: the conditions and the request. */
object WorkScheduler {
    private const val UNIQUE_NAME = "notes-upload"

    fun enqueueUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** ENQUEUED, RUNNING, SUCCEEDED, ... or NONE. Shown in the status strip. */
    fun uploadState(context: Context): Flow<String> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_NAME)
            .map { infos -> infos.firstOrNull()?.state?.name ?: "NONE" }
}

// =====================================================================
// 6. FOREGROUND SERVICE: records the route
// =====================================================================

enum class TrackingMode { OFF, PLAIN, SERVICE }

/** Shared between the ViewModel (plain tracking) and the service, so the UI always knows. */
object TrackingState {
    val mode = MutableStateFlow(TrackingMode.OFF)

    /**
     * Demo only. When true, the app skips its permission check and the service behaves
     * as if location permission were missing, so the audience sees the SecurityException.
     */
    val breakPermission = MutableStateFlow(false)
}

/**
 * Records the courier's route as a foreground service of type "location".
 * The notification is the "recording light": the user can see it and stop it.
 */
class RouteTrackingService : Service() {

    private val tag = "RouteService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var routeDao: RouteDao

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                Log.d(tag, "[service] point ${loc.latitude}, ${loc.longitude}")
                scope.launch {
                    routeDao.insert(RoutePointEntity(lat = loc.latitude, lng = loc.longitude, time = loc.time))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        routeDao = AppDatabase.get(this).routeDao()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Route recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // Permission is checked before the service starts (or deliberately skipped by the demo switch).
    @SuppressLint("MissingPermission", "InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {          // the Stop button in the notification
            stopSelf()
            return START_NOT_STICKY
        }
        if (TrackingState.breakPermission.value) {
            // Demo only: reproduces what Android does when a "location" service starts
            // without location permission. The same exception, at the same moment.
            throw SecurityException(
                "Demo: foreground service of type location started without location permission"
            )
        }
        // 1. Show the notification and declare the reason
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        TrackingState.mode.value = TrackingMode.SERVICE
        // 2. Start asking for a location every 5 seconds
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        return START_STICKY          // 3. If Android kills us, restart
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(callback)      // 4. Stop asking
        scope.cancel()
        TrackingState.mode.value = TrackingMode.OFF
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RouteTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording your route")
            .setContentText("RouteMapper is logging your location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.lect9backgroundwork.STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "route_recording"
    }
}

// =====================================================================
// 7. VIEWMODEL: the state the screens read
// =====================================================================

/** What the Network section shows while and after a fetch. */
sealed interface FetchState {
    object Idle : FetchState
    object Loading : FetchState
    object Done : FetchState
    data class Error(val message: String) : FetchState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val repository = ServiceLocator.notesRepository(app)
    private val fused = LocationServices.getFusedLocationProviderClient(app)

    // ---- State the UI reads ----

    val routePoints: StateFlow<List<RoutePointEntity>> =
        db.routeDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val notes: StateFlow<List<Note>> =
        db.noteDao().observeAll()
            .map { list -> list.map { it.toDomain() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uploadState: StateFlow<String> =
        WorkScheduler.uploadState(app).stateIn(viewModelScope, SharingStarted.Eagerly, "NONE")

    val trackingMode: StateFlow<TrackingMode> = TrackingState.mode

    val breakPermission: StateFlow<Boolean> = TrackingState.breakPermission

    private val _ticks = MutableStateFlow(0)
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    private val _timerRunning = MutableStateFlow(false)
    val timerRunning: StateFlow<Boolean> = _timerRunning.asStateFlow()

    private val _dozeSim = MutableStateFlow(false)
    val dozeSim: StateFlow<Boolean> = _dozeSim.asStateFlow()

    private val _held = MutableStateFlow(0)
    val held: StateFlow<Int> = _held.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _breakMode = MutableStateFlow(BreakMode.NORMAL)
    val breakMode: StateFlow<BreakMode> = _breakMode.asStateFlow()

    // ---- Tracking WITHOUT a service (the "obvious approach") ----

    private val plainCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                Log.d("RouteService", "[plain] point ${loc.latitude}, ${loc.longitude}")
                viewModelScope.launch(Dispatchers.IO) {
                    db.routeDao().insert(RoutePointEntity(lat = loc.latitude, lng = loc.longitude, time = loc.time))
                }
            }
        }
    }

    /** Starts location updates from the screen. Android stops delivering them once the app is in the background. */
    @SuppressLint("MissingPermission")
    fun startPlainTracking() {
        if (TrackingState.mode.value != TrackingMode.OFF) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()
        fused.requestLocationUpdates(request, plainCallback, Looper.getMainLooper())
        TrackingState.mode.value = TrackingMode.PLAIN
    }

    /** Stops whichever kind of tracking is running. */
    fun stopTracking() {
        when (TrackingState.mode.value) {
            TrackingMode.PLAIN -> {
                fused.removeLocationUpdates(plainCallback)
                TrackingState.mode.value = TrackingMode.OFF
            }
            TrackingMode.SERVICE -> {
                val app = getApplication<Application>()
                app.stopService(Intent(app, RouteTrackingService::class.java))
            }
            TrackingMode.OFF -> Unit
        }
    }

    fun clearRoute() {
        viewModelScope.launch(Dispatchers.IO) { db.routeDao().clear() }
    }

    /** Demo switch: start the service without the permission check, so it crashes with a SecurityException. */
    fun setBreakPermission(value: Boolean) {
        TrackingState.breakPermission.value = value
    }

    // ---- Work section ----

    fun addNote() {
        viewModelScope.launch {
            val last = routePoints.value.lastOrNull()
            val count = notes.value.count { !it.fromServer } + 1
            repository.addLocalNote("Delivery #$count", "Parcel left at the door", last?.lat, last?.lng)
        }
    }

    fun uploadNow() {
        WorkScheduler.enqueueUpload(getApplication())
    }

    private var timerJob: Job? = null

    /** The "obvious approach" for Demo 2: a plain coroutine timer. Watch it stall under Doze. */
    fun toggleTimer() {
        if (timerJob?.isActive == true) {
            timerJob?.cancel()
            _timerRunning.value = false
        } else {
            _timerRunning.value = true
            timerJob = viewModelScope.launch {
                while (isActive) {
                    delay(10_000)
                    if (_dozeSim.value) {
                        _held.value += 1
                        Log.d("TimerDemo", "tick held back by simulated Doze (${_held.value} waiting)")
                    } else {
                        _ticks.value += 1
                        Log.d("TimerDemo", "tick ${_ticks.value}")
                    }
                }
            }
        }
    }

    /** Demo switch: pretend Doze is active. Ticks are held back instead of counted. */
    fun setDozeSim(on: Boolean) {
        _dozeSim.value = on
        if (!on) releaseHeld()
    }

    /** Demo button: Android's short "maintenance window". Everything that waited runs at once. */
    fun maintenanceWindow() = releaseHeld()

    private fun releaseHeld() {
        val n = _held.value
        if (n > 0) {
            _ticks.value += n
            _held.value = 0
            Log.d("TimerDemo", "maintenance window: $n waiting ticks released at once")
        }
    }

    // ---- Network section ----

    fun setBreakMode(mode: BreakMode) {
        _breakMode.value = mode
    }

    fun fetchNotes() {
        viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            val result = repository.refreshNotes(userId = 1, mode = _breakMode.value)
            _fetchState.value = result.fold(
                onSuccess = { FetchState.Done },
                onFailure = { FetchState.Error(describe(it)) }
            )
        }
    }

    private fun describe(e: Throwable): String = when {
        e is HttpException -> "HTTP ${e.code()}: the server answered, but the request was wrong"
        e.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
            "Plain http is blocked by Android (cleartext). Use https."
        else -> "No connection or network error. Cached notes are still shown."
    }

    override fun onCleared() {
        fused.removeLocationUpdates(plainCallback)
        if (TrackingState.mode.value == TrackingMode.PLAIN) TrackingState.mode.value = TrackingMode.OFF
        super.onCleared()
    }
}

// =====================================================================
// 8. UI: activity, home screen, status strip and the three sections
// =====================================================================

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen(vm)
                }
            }
        }
    }
}

@Composable
fun AppScreen(vm: MainViewModel) {
    var started by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf("Tracking", "Work", "Network")

    // Back from the main screen returns to the home screen
    BackHandler(enabled = started) { started = false }

    if (!started) {
        HomeScreen(onStart = { started = true })
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            StatusStrip(vm)
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> TrackingSection(vm)
                1 -> WorkSection(vm)
                else -> NetworkSection(vm)
            }
        }
    }
}

/** The starting screen: the app's name, what it does, and one button. */
@Composable
fun HomeScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "RouteMapper",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Record your delivery route, add notes along the way, and upload them when you are ready.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text("Tracking  ·  Work  ·  Network", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start shift") }
        Spacer(Modifier.height(32.dp))
        Text("Lecture 9 · Background work demo", style = MaterialTheme.typography.labelMedium)
    }
}

/** Always visible, so each demo has something to point at. */
@Composable
fun StatusStrip(vm: MainViewModel) {
    val points by vm.routePoints.collectAsState()
    val notes by vm.notes.collectAsState()
    val upload by vm.uploadState.collectAsState()
    val mode by vm.trackingMode.collectAsState()
    val waiting = notes.count { !it.fromServer && !it.uploaded }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Tracking: $mode   ·   Route points: ${points.size}", style = MaterialTheme.typography.bodyMedium)
            Text("Notes waiting: $waiting   ·   Upload: $upload", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- Tracking

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasLocation(context: Context) =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun startRouteService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, RouteTrackingService::class.java))
}

@Composable
fun TrackingSection(vm: MainViewModel) {
    val context = LocalContext.current
    val points by vm.routePoints.collectAsState()
    val notes by vm.notes.collectAsState()
    val mode by vm.trackingMode.collectAsState()
    val breakPermission by vm.breakPermission.collectAsState()
    var message by remember { mutableStateOf("") }
    var pendingStart by remember { mutableStateOf<TrackingMode?>(null) }

    fun launchTarget(target: TrackingMode?) {
        when (target) {
            TrackingMode.PLAIN -> vm.startPlainTracking()
            TrackingMode.SERVICE -> startRouteService(context)
            else -> Unit
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation(context)) {
            launchTarget(pendingStart)
        } else {
            message = "Location permission denied"
        }
        pendingStart = null
    }

    fun begin(target: TrackingMode) {
        message = ""
        // Demo switch: the permission check is skipped and the service crashes with a SecurityException.
        val demoSkip = target == TrackingMode.SERVICE && breakPermission
        val missing = mutableListOf<String>()
        if (!demoSkip && !hasLocation(context)) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (target == TrackingMode.SERVICE && !demoSkip &&
            Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) {
            launchTarget(target)
        } else {
            pendingStart = target
            launcher.launch(missing.toTypedArray())
        }
    }

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(63.4305, 10.3951), 14f)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(points.size, mapLoaded) {
        if (mapLoaded) {
            points.lastOrNull()?.let {
                cameraState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 16f))
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { begin(TrackingMode.PLAIN) },
                enabled = mode == TrackingMode.OFF,
                modifier = Modifier.weight(1f)
            ) { Text("Track (no service)") }
            Button(
                onClick = { begin(TrackingMode.SERVICE) },
                enabled = mode == TrackingMode.OFF,
                modifier = Modifier.weight(1f)
            ) { Text("Track (foreground service)") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.stopTracking() },
                enabled = mode != TrackingMode.OFF,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
            OutlinedButton(
                onClick = { vm.clearRoute() },
                modifier = Modifier.weight(1f)
            ) { Text("Clear route") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = breakPermission, onCheckedChange = { vm.setBreakPermission(it) })
            Text(
                "Demo: start the service without the permission check",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (breakPermission) {
            Text(
                "ON: tapping \"Track (foreground service)\" will crash with a SecurityException.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)

        GoogleMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cameraPositionState = cameraState,
            onMapLoaded = { mapLoaded = true }
        ) {
            if (points.size >= 2) {
                Polyline(points = points.map { LatLng(it.lat, it.lng) }, color = Color(0xFF0B57D0), width = 12f)
            }
            notes.filter { it.lat != null && it.lng != null }.forEach { note ->
                val markerState = rememberMarkerState(key = note.localId.toString(), position = LatLng(note.lat!!, note.lng!!))
                Marker(state = markerState, title = note.title)
            }
        }
    }
}

// ---------------------------------------------------------------- Work

@Composable
fun WorkSection(vm: MainViewModel) {
    val notes by vm.notes.collectAsState()
    val ticks by vm.ticks.collectAsState()
    val timerRunning by vm.timerRunning.collectAsState()
    val dozeSim by vm.dozeSim.collectAsState()
    val held by vm.held.collectAsState()
    val mine = notes.filter { !it.fromServer }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.addNote() }, modifier = Modifier.fillMaxWidth()) {
            Text("Add delivery note")
        }
        Button(onClick = { vm.uploadNow() }, modifier = Modifier.fillMaxWidth()) {
            Text("Upload notes now")
        }
        OutlinedButton(onClick = { vm.toggleTimer() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (timerRunning) "Stop coroutine timer" else "Start coroutine timer")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = dozeSim, onCheckedChange = { vm.setDozeSim(it) })
            Text("Simulate Doze (demo)", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { vm.maintenanceWindow() },
            enabled = dozeSim && held > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Maintenance window") }
        Text("Timer ticks: $ticks   ·   Held back: $held   (one tick every 10 seconds, tag TimerDemo)")
        Text("My delivery notes", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(mine, key = { it.localId }) { note ->
                Text("• ${note.title} — ${if (note.uploaded) "uploaded" else "waiting"}")
            }
        }
    }
}

// ---------------------------------------------------------------- Network

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSection(vm: MainViewModel) {
    val notes by vm.notes.collectAsState()
    val fetchState by vm.fetchState.collectAsState()
    val breakMode by vm.breakMode.collectAsState()
    val serverNotes = notes.filter { it.fromServer }

    val statusText = when (val s = fetchState) {
        FetchState.Idle -> "Tap \"Fetch notes\""
        FetchState.Loading -> "Loading..."
        FetchState.Done -> "Done: ${serverNotes.size} notes from the server"
        is FetchState.Error -> "Error: ${s.message}"
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.fetchNotes() }, modifier = Modifier.fillMaxWidth()) {
            Text("Fetch notes")
        }
        Text("Break it:", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = breakMode == BreakMode.NORMAL, onClick = { vm.setBreakMode(BreakMode.NORMAL) }, label = { Text("Normal") })
            FilterChip(selected = breakMode == BreakMode.BAD_PATH, onClick = { vm.setBreakMode(BreakMode.BAD_PATH) }, label = { Text("Bad path") })
            FilterChip(selected = breakMode == BreakMode.CLEARTEXT, onClick = { vm.setBreakMode(BreakMode.CLEARTEXT) }, label = { Text("http://") })
        }
        Text(statusText)
        LazyColumn(Modifier.weight(1f)) {
            items(serverNotes, key = { it.localId }) { note ->
                Text("• ${note.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
