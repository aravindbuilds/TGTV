package com.tgtv.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.model.VideoMapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TdLib {
    private const val TAG = "TdLib"

    private var client: Client? = null
    private val listeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var filesRoot: File? = null
    private var appContext: Context? = null
    private val sweepRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // -------------------------------------------------- streaming folder

    /**
     * The TDLib bytes cache root: the user-chosen streaming folder when set,
     * otherwise the default `<cacheDir>/tdlib_files`. The database itself stays
     * in the app-private files dir (it is a few MB).
     */
    fun streamRoot(context: Context): File {
        val custom = Credentials.streamDir(context)?.let { path ->
            runCatching { File(path) }.getOrNull()?.takeIf { it.parentFile != null }?.let {
                it.mkdirs()
                if (it.canWrite()) it else null
            }
        }
        return custom ?: File(context.cacheDir, "tdlib_files")
    }

    fun databaseDir(context: Context): File = File(context.filesDir, "tdlib")

    // ---------------- Playback stream cache janitor ----------------

    /**
     * fileId currently being streamed by the player, or null. Set at player
     * create time, invalidated when the player leaves the video.
     */
    @Volatile
    var activeStreamFileId: Int? = null

    private var purgeJob: Job? = null

    /**
     * Cache purge with grace period: [delayMs] (20 s) after the user exits a
     * video stream, cancel still-pending downloads of that file and delete its
     * TDLib file-cache entry so the stream cache cannot grow unbounded.
     * If the same video has been started again within the window the purge is
     * skipped, so instant replays still benefit from the cached bytes.
     *
     * Only runs when the cache retention is "On exit". With a longer retention
     * the bytes are kept deliberately and the age-based sweep below removes
     * them later.
     */
    fun scheduleStreamCachePurge(fileId: Int, delayMs: Long = 20_000L) {
        if (!isStarted) return
        val ctx = appContext
        if (ctx != null && Credentials.cacheRetentionMinutes(ctx) != 0) {
            Log.i(TAG, "cache: retention set, keeping file $fileId for the sweep")
            sweepCacheByAge()
            return
        }
        purgeJob?.cancel()
        purgeJob = appScope.launch {
            delay(delayMs)
            if (activeStreamFileId == fileId) return@launch
            try {
                send(TdApi.CancelDownloadFile(fileId, true))
            } catch (_: TdError) {
            }
            try {
                send(TdApi.DeleteFile(fileId))
            } catch (e: TdError) {
                Log.e(TAG, "cache purge DeleteFile failed: ${e.message}")
            }
            Log.i(TAG, "cache purged for file $fileId")
        }
    }

    /**
     * Age-based disk sweep of the TDLib files root: deletes cached files whose
     * last modification is older than the configured retention.
     *
     * Why this exists: TDLib file ids are session-scoped, so the per-file
     * purge above cannot outlive a process restart, and OptimizeStorage with
     * a size cap frees nothing until the cap is crossed. The sweep is purely
     * timestamp based, survives restarts, and runs when the player exits,
     * periodically while idle, and once at app start. Skipped while streaming.
     */
    fun sweepCacheByAge() {
        val ctx = appContext ?: return
        val root = filesRoot ?: return
        val retentionMin = Credentials.cacheRetentionMinutes(ctx)
        if (retentionMin <= 0) return // "On exit" handled by the per-file purge
        if (activeStreamFileId != null) return
        if (!sweepRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val cutoff = System.currentTimeMillis() - retentionMin * 60_000L
                var freed = 0L
                root.listFiles()?.forEach { entry ->
                    if (entry.isFile) {
                        if (entry.lastModified() < cutoff) {
                            freed += entry.length()
                            entry.delete()
                        }
                    } else {
                        // TDLib partial-download dirs: newest file mtime wins
                        val newest = entry.walkBottomUp().filter { it.isFile }
                            .maxOfOrNull { it.lastModified() } ?: 0L
                        if (newest in 1 until cutoff) {
                            freed += entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                            entry.deleteRecursively()
                        }
                    }
                }
                if (freed > 0L) {
                    Log.i(TAG,
                        "cache sweep: freed %d bytes (retention %d min)".format(freed, retentionMin))
                }
            } finally {
                sweepRunning.set(false)
            }
        }
    }

    private fun dirSize(root: File): Long =
        root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------- bot library store

    /** Remembers a received video for the bot library and persists it. */
    private fun rememberBotVideo(m: TdApi.Message) {
        val chatTitle = chatCache[m.chatId]?.title ?: "Chat"
        VideoMapper.mapMessage(m, chatTitle)?.let {
            // Merge: keep the stored entry when this update is a duplicate,
            // otherwise refresh (file ids renew every session).
            botLibrary[m.id] = it
            appScope.launch(Dispatchers.IO) {
                runCatching { persistBotStore() }
            }
        }
    }

    /** Full items -> bot_library.json (remote ids keep playback alive). */
    private fun persistBotStore() {
        val store = botStoreFile ?: return
        val arr = JSONArray()
        for (v in botLibrary.values) {
            arr.put(
                JSONObject()
                    .put("messageId", v.messageId)
                    .put("chatId", v.chatId)
                    .put("chatTitle", v.chatTitle)
                    .put("title", v.title)
                    .put("fileId", v.fileId)
                    .put("fileSize", v.fileSize)
                    .put("durationSeconds", v.durationSeconds)
                    .put("mimeType", v.mimeType)
                    .put("fileName", v.fileName)
                    .put("thumbnailFileId", v.thumbnailFileId)
                    .put("remoteFileId", v.remoteFileId)
                    .put("fileKind", v.fileKind),
            )
        }
        store.writeText(arr.toString())
    }

    /** Restores the persisted library at [start]; ids refresh at playback. */
    private fun loadBotStore() {
        val store = botStoreFile ?: return
        if (!store.exists()) return
        runCatching {
            val arr = JSONArray(store.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = VideoItem(
                    messageId = o.getLong("messageId"),
                    chatTitle = o.optString("chatTitle"),
                    title = o.optString("title"),
                    fileId = o.getInt("fileId"),
                    fileSize = o.getLong("fileSize"),
                    durationSeconds = o.getInt("durationSeconds"),
                    mimeType = o.optString("mimeType"),
                    fileName = o.optString("fileName"),
                    thumbnailFileId = o.getInt("thumbnailFileId"),
                    chatId = o.getLong("chatId"),
                    remoteFileId = o.optString("remoteFileId"),
                    fileKind = o.optString("fileKind", "video"),
                )
                botLibrary[item.messageId] = item
            }
            Log.i(TAG, "bot library restored from store: ${botLibrary.size} items")
        }
    }

    /** Persisted + live bot videos, newest first, for [reloadBot] in HomeActivity. */
    fun botVideos(): List<VideoItem> =
        botLibrary.values.sortedByDescending { it.messageId }

    /**
     * Returns a live file id for [video]: session-restored rows carry a stale
     * TDLib file id, which re-resolves from the stable remote id; falling
     * back to a direct GetMessage for rows with chat coordinates.
     */
    suspend fun resolveFileId(video: VideoItem): Int {
        if (isStarted && video.remoteFileId.isNotBlank()) {
            val fresh = runCatching {
                send(TdApi.GetRemoteFile(video.remoteFileId, fileTypeFor(video.fileKind)))
            }.getOrNull() as? TdApi.File
            if (fresh != null && fresh.id > 0) return fresh.id
            Log.i(TAG, "resolveFileId: remote re-resolve failed for '${video.title}'")
        }
        if (isStarted && video.chatId != 0L && video.messageId != 0L) {
            val msg = runCatching {
                send(TdApi.GetMessage(video.chatId, video.messageId)) as? TdApi.Message
            }.getOrNull()
            if (msg != null) {
                VideoMapper.mapMessage(msg, video.chatTitle)?.let { return it.fileId }
            }
        }
        return video.fileId
    }

    fun fileTypeFor(kind: String): TdApi.FileType = when (kind) {
        "note" -> TdApi.FileTypeVideoNote()
        "animation" -> TdApi.FileTypeAnimation()
        "document" -> TdApi.FileTypeDocument()
        else -> TdApi.FileTypeVideo()
    }

    /**
     * Manual "Clear cache" from Settings: asks TDLib to release every cached
     * file (streamed video + thumbnails), then removes anything left in the
     * streaming root on disk. Returns bytes freed. Refuses while a video is
     * actively streaming.
     */
    suspend fun clearCache(): Long {
        if (isStarted && activeStreamFileId != null) return 0
        val root = filesRoot ?: return 0
        val before = dirSize(root)
        if (isStarted) {
            runCatching {
                // size=0: drop the whole TDLib file cache
                send(
                    TdApi.OptimizeStorage(
                        0,
                        -1,
                        -1,
                        -1,
                        emptyArray<TdApi.FileType>(),
                        LongArray(0),
                        LongArray(0),
                        false,
                        -1,
                    ),
                )
            }
        }
        runCatching {
            root.listFiles()?.forEach { it.deleteRecursively() }
        }
        val after = dirSize(root)
        Log.i(TAG, "clear cache: %d -> %d bytes".format(before, after))
        return (before - after).coerceAtLeast(0)
    }

    /**
     * Logs the TDLib cache size every 15 s while a video is streaming, so
     * stutter reports can be checked against cache growth ("is the cache
     * leaking?"). Cheap: one walk of the cache dir per interval. When idle
     * the retention sweep runs about once a minute.
     */
    fun startCacheMonitor() {
        appScope.launch {
            var ticks = 0
            while (isStarted) {
                if (activeStreamFileId != null) {
                    val root = filesRoot
                    val mb = root?.let { dirSize(it) }?.ushr(20) ?: 0L
                    Log.i(TAG, "cache monitor: streaming, tdlib_files = ${mb}MB")
                } else if (ticks % 4 == 0) {
                    sweepCacheByAge()
                }
                ticks++
                delay(15_000L)
            }
        }
    }

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState.asStateFlow()

    private val _authErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val authErrors = _authErrors.asSharedFlow()

    /**
     * Telegram phone-app link shown as a QR code while device-link (QR) login
     * is in progress; null when no such QR is being displayed.
     */
    private val _qrLink = MutableStateFlow<String?>(null)
    val qrLink: StateFlow<String?> = _qrLink.asStateFlow()

    val latestFiles = ConcurrentHashMap<Int, TdApi.File>()

    /**
     * Chats seen through TDLib updates. Bots can't call LoadChats/GetChats in
     * TDLib 1.8+ ("method is not available to bots"), so the session's chat list
     * is built from UpdateNewChat / UpdateChatTitle instead.
     */
    val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    /**
     * Video messages captured live per chat. Bots cannot read history in
     * TDLib 1.8+ (GetChatHistory / SearchChatMessages are user-only), so a bot
     * session sees only messages posted while it is connected.
     */
    val liveMessages = ConcurrentHashMap<Long, MutableList<TdApi.Message>>()

    /**
     * Pulls the user's main chat list page by page via LoadChats (user
     * sessions only - bots cannot call it). Loaded chats stream in as
     * UpdateNewChat and land in [chatCache]. Stops when TDLib reports the
     * list is exhausted or after a hard cap of 5 000 chats.
     */
    suspend fun loadChatsBlocking(limit: Int = 100) {
        if (!isStarted) return
        repeat(50) {
            try {
                send(TdApi.LoadChats(TdApi.ChatListMain(), limit))
            } catch (_: TdError) {
                return
            }
        }
    }

    /**
     * The authorized account (fetched with GetMe after Ready). Lets the UI
     * trust the live session type instead of the login-mode pref, which can
     * go stale when pairing via phone QR without passing LoginActivity.
     */
    @Volatile
    var me: TdApi.User? = null
        private set

    /** True when the live TDLib session is a real user account (not a bot). */
    val sessionIsUser: Boolean get() = me?.type is TdApi.UserTypeRegular

    /**
     * Bot-mode library. Bots cannot call GetChatHistory / SearchChatMessages,
     * so the only videos available are the ones received live - which would
     * vanish on every restart. [botLibrary] therefore persists the mapped
     * items (with TDLib's stable remote ids) to disk, like the TV app's
     * VideoLibrary; playback re-resolves stale file ids via GetRemoteFile.
     */
    val botLibrary = ConcurrentHashMap<Long, VideoItem>()
    private var botStoreFile: File? = null

    /**
     * Resolves [me] with GetMe if needed and returns [sessionIsUser]. Cheap
     * once resolved; call before branching on bot/user behaviour.
     */
    suspend fun ensureMe(): Boolean {
        if (me != null) return sessionIsUser
        if (!isStarted) return false
        me = runCatching { send(TdApi.GetMe()) as? TdApi.User }.getOrNull()
        return sessionIsUser
    }

    private var authJob: Job? = null
    private var sentParameters = false

    /**
     * BotFather token captured at [start] (and at [loginBot]) so the auth
     * loop can re-authenticate a bot session automatically on cold start.
     */
    private var botTokenCache: String? = null

    /** True while a bot auth request is in flight; blocks duplicate sends. */
    private var botAuthInFlight = false

    val isStarted: Boolean get() = client != null

    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        botTokenCache = Credentials.botToken(context)
        botStoreFile = File(context.filesDir, "bot_library.json")
        runCatching { loadBotStore() }
        // Reset all session-observables BEFORE creating the client: TDLib can
        // report its first auth state within milliseconds of Client.create and
        // a later reset would silently swallow it (login would hang forever).
        _authState.value = null
        _qrLink.value = null
        chatCache.clear()
        authJob?.cancel()
        sentParameters = false
        try {
            Client.setLogMessageHandler(1) { _, _ -> }
            client = Client.create(
                { obj -> dispatchUpdate(obj) },
                { _ -> Unit },
                { t -> Log.e(TAG, "tdlib exception", t) },
            )
        } catch (t: Throwable) {
            // UnsatisfiedLinkError etc: leave UI usable, send() will surface the failure
            Log.e(TAG, "TDLib client creation failed", t)
            return
        }
        client?.send(TdApi.SetLogVerbosityLevel(1), null)
        Log.i(TAG, "start: client created")
        // Inform TDLib of the link type (used for offline stats / backoff)
        client?.send(TdApi.SetNetworkType(currentNetworkType(context)), null)
        // Make sure the database dirs exist
        databaseDir(context).mkdirs()
        filesRoot = streamRoot(context).apply { mkdirs() }
        // Drop anything a previous session left beyond the retention window
        sweepCacheByAge()
        authJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                authState.collect { st ->
                    when (st) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> if (!sentParameters) {
                        sentParameters = true
                        setTdlibParametersBlocking(context)
                    }
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        Log.i(TAG, "authJob: waiting for phone")
                        // Bot sessions re-auth automatically from the saved
                        // BotFather token; user sessions wait for the login
                        // screen to call [requestPhoneCode] - nothing auto-sent.
                        botTokenCache?.let { token ->
                            Log.i(TAG, "authJob: saved bot token found, signing in as bot")
                            sendBotAuth(token)
                        }
                    }
                    is TdApi.AuthorizationStateReady -> {
                        authJob?.cancel()
                        appScope.launch {
                            runCatching { ensureMe() }
                        }
                        startCacheMonitor()
                    }
                    else -> Unit
                }
                }
            }.onFailure { f ->
                // Self-cancel on Ready is normal, not an error
                if (f !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "authJob collect failed", f)
                }
            }
        }
    }

    fun currentNetworkType(context: Context): TdApi.NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        return if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) {
            TdApi.NetworkTypeWiFi()
        } else {
            TdApi.NetworkTypeMobile()
        }
    }

    // ---------------- Phone (user) login actions ----------------

    /** Step 1: request the SMS/Telegram code for this phone number. */
    fun requestPhoneCode(phone: String) {
        appScope.launch {
            runCatching {
                send(
                    TdApi.SetAuthenticationPhoneNumber(
                        phone.trim(),
                        TdApi.PhoneNumberAuthenticationSettings(
                            false, false, false, false, false, null, arrayOf<String>(),
                        ),
                    ),
                )
            }.onFailure { error ->
                _authErrors.emit(error.message ?: "Could not send code")
            }
        }
    }

    fun resendPhoneCode() {
        appScope.launch { runCatching { send(TdApi.ResendAuthenticationCode()) } }
    }

    /** Step 2: the login code (Telegram message or SMS). */
    fun submitCode(code: String) {
        appScope.launch {
            runCatching { send(TdApi.CheckAuthenticationCode(code.trim())) }
                .onFailure { error -> _authErrors.emit(error.message ?: "Wrong code") }
        }
    }

    /** Step 3: optional cloud password for 2FA-protected accounts. */
    fun submitPassword(password: String) {
        appScope.launch {
            runCatching { send(TdApi.CheckAuthenticationPassword(password)) }
                .onFailure { error -> _authErrors.emit(error.message ?: "Wrong password") }
        }
    }

    /**
     * Alternative login: BotFather bot token instead of a phone number.
     * Valid while TDLib is at the WaitPhoneNumber state; on failure TDLib
     * returns to that state so the phone form is shown again.
     */
    fun loginBot(token: String) {
        botTokenCache = token.trim()
        sendBotAuth(token.trim())
    }

    /**
     * Sends CheckAuthenticationBotToken once: the auth loop (saved token) and
     * the login button can race here, and a double send surfaces as a bogus
     * "already authorized" error in the login status.
     */
    private fun sendBotAuth(token: String) {
        if (botAuthInFlight) return
        botAuthInFlight = true
        appScope.launch {
            runCatching { send(TdApi.CheckAuthenticationBotToken(token)) }
                .onFailure { error ->
                    // Allow a retry after a failed attempt
                    botAuthInFlight = false
                    _authErrors.emit(error.message ?: "Bot sign-in failed")
                }
        }
    }

    fun stop() {
        authJob?.cancel()
        authJob = null
    }

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** Fire-and-forget send (results/updates still flow through [dispatchUpdate]). */
    fun fire(function: TdApi.Function<*>) {
        client?.send(function, Client.ResultHandler { })
    }

    fun addListener(listener: (TdApi.Object) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (TdApi.Object) -> Unit) = listeners.remove(listener)

    private fun dispatchUpdate(o: TdApi.Object) {
        if (o is TdApi.UpdateAuthorizationState) {
            Log.i(TAG, "auth state -> ${o.authorizationState::class.java.simpleName}")
        }
        when (o) {
            is TdApi.UpdateAuthorizationState -> {
                val st = o.authorizationState
                _authState.value = st
                when (st) {
                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                        _qrLink.value = st.link
                    is TdApi.AuthorizationStateReady ->
                        _qrLink.value = null
                    else -> Unit
                }
            }
            is TdApi.UpdateFile -> latestFiles[o.file.id] = o.file
            is TdApi.UpdateNewChat -> {
                o.chat.title?.let { chatCache[o.chat.id] = o.chat }
            }
            is TdApi.UpdateChatTitle -> chatCache[o.chatId]?.let { it.title = o.title }
            is TdApi.UpdateChatLastMessage -> chatCache[o.chatId]?.also { c -> o.lastMessage?.let { c.lastMessage = it } }
            is TdApi.UpdateNewMessage -> if (VideoMapper.isVideoMessage(o.message.content)) {
                liveMessages
                    .getOrPut(o.message.chatId) { CopyOnWriteArrayList() }
                    .add(o.message)
                rememberBotVideo(o.message)
            }
            else -> Unit
        }
        listeners.forEach { l -> runCatching { l(o) } }
    }

    private fun setTdlibParametersBlocking(context: Context) {
        val p = TdApi.SetTdlibParameters(
            false,
            databaseDir(context).absolutePath,
            streamRoot(context).absolutePath,
            null,                       // no database encryption
            true, true, true, false,    // file / chat info / message databases
            Credentials.apiId(context),
            Credentials.apiHash(context).orEmpty(),
            "en-US", "TGTV", "Android", "1.0",
        )
        Log.i(TAG, "sending SetTdlibParameters apiId=${Credentials.apiId(context)}")
        client?.send(p) { result ->
            Log.i(TAG, "SetTdlibParameters result: ${result::class.java.simpleName}")
            if (result is TdApi.Error) {
                _authErrors.tryEmit("TDLib init failed: ${result.message}")
            }
        }
    }

    suspend fun logOut() {
        _qrLink.value = null
        runCatching { send(TdApi.LogOut()) }
    }

    /**
     * Closes the client and deletes the whole TDLib database. Needed when
     * switching login types (bot -> user/QR): TDLib only allows one session per
     * client and a stale bot session makes user-only methods fail.
     */
    suspend fun resetDb(context: Context) {
        authJob?.cancel()
        authJob = null
        botTokenCache = null
        botAuthInFlight = false
        botLibrary.clear()
        runCatching { botStoreFile?.delete() }
        _qrLink.value = null
        _authState.value = null
        latestFiles.clear()
        chatCache.clear()
        liveMessages.clear()
        me = null
        sentParameters = false
        val c = client
        client = null
        c?.let {
            Log.i(TAG, "resetDb: closing TDLib client")
            it.send(TdApi.Close(), Client.ResultHandler { r ->
                if (r is TdApi.Error) Log.e(TAG, "Close failed: ${r.code} ${r.message}")
                else Log.i(TAG, "resetDb: Close accepted")
            })
        }
        withContext(Dispatchers.IO) {
            // Let TDLib flush before deleting its files
            delay(800L)
            databaseDir(context).deleteRecursively()
            streamRoot(context).deleteRecursively()
            Log.i(TAG, "resetDb: db deleted")
        }
    }

    fun close() {
        client = null
    }

    /** Sends [function] and suspends until the result arrives; throws [TdError] on error responses. */
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client ?: run {
                cont.resumeWithException(IllegalStateException("TDLib not started"))
                return@suspendCancellableCoroutine
            }
            c.send(function, Client.ResultHandler { obj ->
                if (obj is TdApi.Error) {
                    Log.e(TAG, "${function.javaClass.simpleName} failed: ${obj.code} ${obj.message}")
                    if (cont.isActive) cont.resumeWithException(TdError.from(obj))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    if (cont.isActive) cont.resume(obj as T)
                }
            })
        }

    // ---------------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------------

    /**
     * TDLib allows at most one pending range download per file: a new
     * [TdApi.DownloadFile] cancels the in-flight one. All range downloads
     * therefore go through a global mutex so concurrent loader threads
     * (ExoPlayer opens several DataSources at once) cannot cancel each other.
     */
    private val rangeDownloadMutex = Mutex()

    /** Synchronously downloads the byte range [offset, offset+limit) and returns once cached. */
    suspend fun downloadRange(
        fileId: Int,
        offset: Long,
        limit: Long,
        priority: Int = 32,
    ): TdApi.File = rangeDownloadMutex.withLock {
        send(TdApi.DownloadFile(fileId, priority, offset, limit, true))
    }

    /**
     * Downloads [fileId] fully and suspends until the local copy exists.
     * Uses [TdApi.DownloadFile] with default offset/limit (whole file).
     * TDLib answers the request itself (no UpdateFile) when the file is
     * already in its cache, so the direct result is checked first - waiting
     * for an update alone would hang forever on cached files.
     */
    suspend fun downloadFully(fileId: Int, priority: Int = 32): TdApi.File {
        // Fast path: already known to be complete
        latestFiles[fileId]?.let { existing ->
            if (existing.local?.isDownloadingCompleted == true) return existing
        }
        val direct = send(TdApi.DownloadFile(fileId, priority, 0, 0, false))
        if (direct.local?.isDownloadingCompleted == true) {
            latestFiles[fileId] = direct
            return direct
        }
        return waitForFileUpdate(fileId) { file ->
            file.local?.isDownloadingCompleted == true
        }
    }

    /** Suspends until an update for [fileId] matching [predicate] arrives (or is already stored). */
    suspend fun waitForFileUpdate(
        fileId: Int,
        predicate: (TdApi.File) -> Boolean,
    ): TdApi.File {
        latestFiles[fileId]?.let { if (predicate(it)) return it }
        var resumed = false
        return suspendCancellableCoroutine { cont ->
            lateinit var listener: (TdApi.Object) -> Unit
        listener = { o ->
            if (o is TdApi.UpdateFile && o.file.id == fileId && predicate(o.file)) {
                listeners.remove(listener)
                if (!resumed && cont.isActive) {
                    resumed = true
                    cont.resume(o.file)
                }
            }
        }
        listeners.add(listener)
            cont.invokeOnCancellation { listeners.remove(listener) }
        }
    }
}
