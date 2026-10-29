package com.tgtv.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tgtv.mobile.R
import com.tgtv.mobile.model.ChatSelection
import com.tgtv.mobile.td.TdLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.format.DateUtils
import android.widget.Toast
import org.drinkless.tdlib.TdApi

/**
 * Chat picker: the user chooses which Telegram chats/groups the library
 * scans for movies and series. Shown once after login (first run) and again
 * from Settings for re-configuration.
 *
 * Chats stream in from TDLib updates; the list is sorted latest-first and
 * defaults to Saved Messages (user sessions) / the owner chat (bot sessions).
 */
class ChatSelectActivity : AppCompatActivity() {

    private class Row(
        val chat: TdApi.Chat,
        val label: String,
        val sub: String,
        var checked: Boolean,
    )

    private val rows = mutableListOf<Row>()
    private val shown = mutableListOf<Row>()
    private lateinit var adapter: ChatAdapter
    private lateinit var txtEmpty: TextView
    private lateinit var txtCount: TextView

    /** True when opened from Settings (back allowed, returns there). */
    private var settingsMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_select)

        settingsMode = intent.getBooleanExtra(EXTRA_SETTINGS, false)
        val back = findViewById<ImageButton>(R.id.btnBack)
        back.visibility = if (settingsMode) View.VISIBLE else View.GONE
        back.setOnClickListener { finish() }

        txtEmpty = findViewById(R.id.txtEmpty)
        txtCount = findViewById(R.id.txtCount)
        val list = findViewById<RecyclerView>(R.id.list)
        adapter = ChatAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<EditText>(R.id.txtSearch).addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
            },
        )

        findViewById<Button>(R.id.btnContinue).setOnClickListener { saveAndContinue() }

        loadChats()
    }

    @Suppress("BackIndentation")
    private fun loadChats() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                txtEmpty.visibility = View.VISIBLE
                txtEmpty.text = "Loading your chats..."
            }
            // Resolve the account type (decides the default pre-check).
            runCatching { TdLib.ensureMe() }
            // User sessions: pull the main chat list (bots receive their
            // chats as live updates instead; for them this call is a no-op).
            runCatching { TdLib.loadChatsBlocking() }
            // Bot sessions: chats may not stream in until a message arrives.
            // Seed the chats referenced by the persisted bot library directly
            // (GetChat works for bots even though LoadChats does not).
            runCatching {
                TdLib.botVideos().map { it.chatId }.distinct().forEach { id ->
                    (TdLib.send(TdApi.GetChat(id)) as? TdApi.Chat)?.let {
                        TdLib.chatCache.putIfAbsent(id, it)
                    }
                }
            }
            // Chats keep streaming in while you're here (new messages, new
            // groups) - the list refreshes itself live until you continue.
            var lastSize = -1
            var waitedMs = 0L
            while (coroutineContext.isActive) {
                if (TdLib.chatCache.size != lastSize) {
                    lastSize = TdLib.chatCache.size
                    withContext(Dispatchers.Main) { buildRows() }
                }
                delay(600)
                waitedMs += 600
                // Nothing arrived after a while: swap the spinner text for
                // instructions so the screen never looks dead.
                if (waitedMs == 15_000L && TdLib.chatCache.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        txtEmpty.text =
                            "No chats found yet.\n\nForward a video to the bot's chat or add the " +
                                "bot to your movie groups - new chats appear here automatically."
                    }
                }
            }
        }
    }

    private fun buildRows() {
        val saved = ChatSelection.selected(this)
        val configured = ChatSelection.isConfigured(this)
        // Ticks survive the live rebuilds: keep whatever the user changed.
        val touched = rows.associate { it.chat.id to it.checked }

        val chats = TdLib.chatCache.values
            .filter { it.type != null }
            .sortedByDescending { it.lastMessage?.date ?: 0 }

        // Defaults on first run: Saved Messages (self chat) for user
        // sessions; the most recently active private chat for bots (the
        // owner who sends the videos).
        val isUser = TdLib.me?.type is TdApi.UserTypeRegular
        val meId = TdLib.me?.id
        val defaultChecked: Set<Long> = when {
            configured -> saved
            isUser && meId != null -> setOf(meId)
            else -> chats.firstOrNull { it.type is TdApi.ChatTypePrivate }
                ?.let { setOf(it.id) } ?: emptySet()
        }

        rows.clear()
        rows.addAll(
            chats.map { chat ->
                val checked = when {
                    touched.containsKey(chat.id) -> touched[chat.id]!!
                    configured -> chat.id in saved
                    else -> chat.id in defaultChecked
                }
                Row(chat, typeLabel(chat), subLine(chat), checked)
            },
        )
        // Bot sessions only learn about chats through activity - explain.
        findViewById<TextView>(R.id.txtHint).visibility =
            if (isUser || rows.isEmpty()) View.GONE else View.VISIBLE
        applyFilter()
    }

    private fun typeLabel(chat: TdApi.Chat): String = when (val t = chat.type) {
        is TdApi.ChatTypePrivate ->
            if (t.userId == TdLib.me?.id) "Saved Messages" else "Chat"
        is TdApi.ChatTypeBasicGroup -> "Group"
        is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Group"
        is TdApi.ChatTypeSecret -> "Secret chat"
        else -> "Chat"
    }

    private fun subLine(chat: TdApi.Chat): String {
        val date = chat.lastMessage?.date ?: 0
        val when_ = if (date > 0) {
            DateUtils.getRelativeTimeSpanString(
                date * 1000L,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        } else {
            "no recent activity"
        }
        return "${typeLabel(chat)} · $when_"
    }

    /** Live search: keeps only chats whose title contains the query. */
    private fun applyFilter() {
        val q = (findViewById<EditText>(R.id.txtSearch).text ?: "").toString()
            .trim().lowercase()
        shown.clear()
        shown.addAll(if (q.isEmpty()) rows else rows.filter { it.chat.title.lowercase().contains(q) })
        adapter.notifyDataSetChanged()
        txtEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        updateCount()
    }

    private fun updateCount() {
        txtCount.text = "${rows.count { it.checked }} selected"
    }

    private fun saveAndContinue() {
        val ids = rows.filter { it.checked }.map { it.chat.id }.toSet()
        if (ids.isEmpty()) {
            Toast.makeText(this, "Pick at least one chat", Toast.LENGTH_SHORT).show()
            return
        }
        ChatSelection.save(this, ids)
        HomeActivity.chatsChanged = true
        if (settingsMode) {
            finish()
        } else {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private inner class ChatAdapter : RecyclerView.Adapter<ChatHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatHolder =
            ChatHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_chat_pick, parent, false),
            )

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: ChatHolder, position: Int) {
            val row = shown[position]
            holder.title.text = row.chat.title
            holder.sub.text = row.sub
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = row.checked
            holder.check.setOnCheckedChangeListener { _, checked ->
                row.checked = checked
                updateCount()
            }
            holder.itemView.setOnClickListener {
                row.checked = !row.checked
                holder.check.isChecked = row.checked
                updateCount()
            }
        }
    }

    private class ChatHolder(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val title: TextView = v.findViewById(R.id.txtTitle)
        val sub: TextView = v.findViewById(R.id.txtSub)
    }

    companion object {
        private const val EXTRA_SETTINGS = "settings"

        fun intent(context: android.content.Context, fromSettings: Boolean): Intent =
            Intent(context, ChatSelectActivity::class.java).putExtra(EXTRA_SETTINGS, fromSettings)
    }
}
