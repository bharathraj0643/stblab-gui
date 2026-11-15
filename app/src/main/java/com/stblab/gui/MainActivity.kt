package com.stblab.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.xstream.remote.RemoteV2Client
import java.util.concurrent.Executors

/** Native Android TV UI. */
class MainActivity : Activity() {
    private lateinit var remote: RemoteV2Client
    private lateinit var store: ChannelStore
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private var page = "picker"
    private var settingsSection = ""
    private var genres: List<GenreDto> = emptyList()
    private var genreIndex = 0
    private var channelIndex = 0
    private var selectedByGenre = mutableMapOf<Int, Int>()
    private var pageIndex = 0
    private var keypadOpen = false
    private var dial = ""
    private var dialTask: Runnable? = null
    private var channelStepTask: Runnable? = null
    private var lastTuneIds = mutableListOf<Int>()
    private var lastTuned = ""
    private var tuningStatus = ""
    private var dialogError = ""
    private var activeDiscovery: Discovery? = null
    private var boxes: List<Discovery.Box> = emptyList()
    private var discoveryText = "Scan to find Android TV Remote devices on this network."
    private var hostField: EditText? = null
    private var codeField: EditText? = null
    private var selectedEditGenre = 0
    private var focusedArea = "channels"
    private var editFavorites = false
    private var jsonDraft = ""
    private var focusRestoreId = View.NO_ID
    private var pickerConnectionLabel: TextView? = null
    private var pickerTelemetryLabel: TextView? = null
    private var pickerTuneLabel: TextView? = null
    private var settingsRemoteLabel: TextView? = null
    private var pairingStatusLabel: TextView? = null
    private val poll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                refreshStatusViews()
                main.postDelayed(this, 3000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        remote = RemoteV2Client.get(this)
        store = ChannelStore(this)
        reloadGenres()
        renderPicker()
        main.postDelayed(poll, 3000)
    }

    override fun onResume() { super.onResume(); if (::remote.isInitialized) { if (page == "picker") renderPicker() else renderSettings() } }
    override fun onDestroy() { activeDiscovery?.stop(); dialTask?.let(main::removeCallbacks); main.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy() }

    override fun onBackPressed() {
        when {
            page == "settings" -> { page = "picker"; renderPicker() }
            page == "picker" -> super.onBackPressed()
            else -> { page = "settings"; renderSettings() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (page != "picker") return super.onKeyDown(keyCode, event)
        if (keyCode != KeyEvent.KEYCODE_CHANNEL_UP && keyCode != KeyEvent.KEYCODE_PAGE_UP &&
            keyCode != KeyEvent.KEYCODE_CHANNEL_DOWN && keyCode != KeyEvent.KEYCODE_PAGE_DOWN) {
            if (channelStepTask != null) tuningStatus = ""
            channelStepTask?.let(main::removeCallbacks)
            channelStepTask = null
            pickerTuneLabel?.visibility = View.GONE
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { stepChannel(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { stepChannel(1); return true }
            229 -> { recall(); return true }
            177 -> { openFavorites(); return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { digit((keyCode - KeyEvent.KEYCODE_0).toString()); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { digit((keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()); return true }
        }
        if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT && handlePickerDpad(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun reloadGenres() {
        genres = runCatching { store.genres() }.getOrDefault(emptyList())
        if (genreIndex !in displayGenres().indices) genreIndex = 0
        val list = currentChannels()
        channelIndex = channelIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        pageIndex = channelIndex / PAGE_SIZE
    }

    private fun displayGenres(): List<GenreDto> {
        val favorites = genres.flatMap { it.channels }.filter { it.isFavorite }.sortedWith(compareBy<ChannelDto> { it.favoriteOrder }.thenBy { it.id })
        return if (favorites.isEmpty()) genres else listOf(GenreDto(-1, "⭐ Favorites", -1, favorites)) + genres
    }
    private fun currentGenre(): GenreDto? = displayGenres().getOrNull(genreIndex)
    private fun currentChannels(): List<ChannelDto> = currentGenre()?.channels.orEmpty().sortedWith(compareBy<ChannelDto> { it.sortOrder }.thenBy { it.id })
    private fun jumpGenre(index: Int) {
        val old = currentGenre()?.id
        if (old != null) selectedByGenre[old] = channelIndex
        genreIndex = index.coerceIn(0, (displayGenres().size - 1).coerceAtLeast(0))
        channelIndex = selectedByGenre[currentGenre()?.id] ?: 0
        channelIndex = channelIndex.coerceIn(0, (currentChannels().size - 1).coerceAtLeast(0))
        pageIndex = channelIndex / PAGE_SIZE
        renderPicker()
    }

    private fun renderPicker() {
        if (!::store.isInitialized || page != "picker") return
        reloadGenres()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18)); isFocusable = true }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(label("StbLab Picker", 27, WHITE, true), weight(1f))
        val state = remote.snapshot()
        val connectionLabel = label(if (state.connected) "● LIVE" else "● OFFLINE", 15, if (state.connected) GREEN else AMBER, true)
        pickerConnectionLabel = connectionLabel
        header.addView(connectionLabel)
        header.addView(button("⚙  SETTINGS") { focusRestoreId = SETTINGS_BUTTON_ID; page = "settings"; renderSettings() }.apply { id = SETTINGS_BUTTON_ID })
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val genreColumn = column("GENRE", 0.23f)
        val dg = displayGenres()
        dg.forEachIndexed { i, genre ->
            val item = button("${genre.name}   ${genre.channels.size}") { jumpGenre(i); focusChannelAt(channelIndex) }.apply { id = GENRE_ID_BASE + i }
            item.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            item.setBackground(panel(i == genreIndex, GENRE_SURFACE))
            item.isSelected = i == genreIndex
            item.setTextColor(if (i == genreIndex) Color.BLACK else DIM)
            item.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                item.setBackground(panel(focused || i == genreIndex, GENRE_SURFACE))
                item.setTextColor(if (focused || i == genreIndex) Color.BLACK else DIM)
                if (focused) {
                    focusRestoreId = GENRE_ID_BASE + i
                    focusedArea = "genre"
                    if (genreIndex != i) {
                        val oldId = currentGenre()?.id
                        if (oldId != null) selectedByGenre[oldId] = channelIndex
                        genreIndex = i
                        channelIndex = selectedByGenre[currentGenre()?.id] ?: 0
                        channelIndex = channelIndex.coerceIn(0, (currentChannels().size - 1).coerceAtLeast(0))
                        pageIndex = channelIndex / PAGE_SIZE
                        renderPicker()
                    }
                }
            }
            genreColumn.addView(item)
        }
        body.addView(genreColumn, weight(0.23f))

        val channelColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        val channels = currentChannels()
        val chunks = ((channels.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val pageRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pageRow.addView(button("◀  PREV") { if (chunks > 1) { pageIndex = (pageIndex - 1 + chunks) % chunks; channelIndex = pageIndex * PAGE_SIZE; renderPicker(); focusChannelAt(channelIndex) } })
        pageRow.addView(label("Page ${pageIndex + 1} of $chunks  ·  ${currentGenre()?.name ?: "Channels"} · ${channels.size} ch", 16, MUTED, true), weight(1f))
        pageRow.addView(button("NEXT  ▶") { if (chunks > 1) { pageIndex = (pageIndex + 1) % chunks; channelIndex = pageIndex * PAGE_SIZE; renderPicker(); focusChannelAt(channelIndex) } })
        channelColumn.addView(pageRow)
        val start = pageIndex * PAGE_SIZE
        channels.drop(start).take(PAGE_SIZE).forEachIndexed { offset, ch ->
            val absolute = start + offset
            val row = button("${ch.dummyNumber.toString().padStart(3, ' ')}    ${ch.name}    → ${ch.redirectNumber}") {}.apply { id = CHANNEL_ID_BASE + absolute }
            row.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                row.setBackground(panel(focused || absolute == channelIndex, CHANNEL_SURFACE))
                row.setTextColor(if (focused || absolute == channelIndex) Color.BLACK else TEXT)
                if (focused) { focusedArea = "channels"; channelIndex = absolute; selectedByGenre[currentGenre()?.id ?: 0] = absolute; pageIndex = absolute / PAGE_SIZE; focusRestoreId = CHANNEL_ID_BASE + absolute }
            }
            row.setOnClickListener { channelIndex = absolute; selectedByGenre[currentGenre()?.id ?: 0] = absolute; tune(ch) }
            row.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            row.setBackground(panel(absolute == channelIndex, CHANNEL_SURFACE))
            row.setTextColor(if (absolute == channelIndex) Color.BLACK else TEXT)
            row.contentDescription = "${ch.name}, channel ${ch.dummyNumber}. Press OK to tune."
            channelColumn.addView(row)
        }
        if (channels.isEmpty()) channelColumn.addView(label("No channels found in this genre.", 18, MUTED))
        body.addView(channelColumn, weight(0.52f))

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        val chosen = channels.getOrNull(channelIndex)
        val telemetry = buildString {
            appendLine("POWER    ${state.isOn?.let { if (it) "ON" else "STANDBY" } ?: "—"}")
            appendLine("VOLUME   ${state.volumeLevel?.let { "$it/${state.volumeMax}${if (state.muted == true) " MUTE" else ""}" } ?: "—"}")
            appendLine("APP      ${state.currentApp?.substringAfterLast('.') ?: "TV Home"}")
            appendLine("TUNED    ${lastTuned.ifBlank { "—" }}")
        }
        val telemetryLabel = label(telemetry, 15, TEXT).apply { setBackground(panel(false, 0xff03061e.toInt())) }
        pickerTelemetryLabel = telemetryLabel
        side.addView(telemetryLabel)
        side.addView(button(if (keypadOpen) "HIDE NUMPAD" else "🔢  NUMPAD") { focusRestoreId = KEYPAD_TOGGLE_ID; keypadOpen = !keypadOpen; renderPicker() }.apply { id = KEYPAD_TOGGLE_ID })
        if (keypadOpen) {
            side.addView(label("Focus: CHANNELS · ${chosen?.let { "#${it.dummyNumber} ${it.name}" } ?: "No Channel"}", 14, MUTED))
            val digits = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("CH ▲", "0", "CH ▼"))
            digits.forEachIndexed { rowIndex, values ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                values.forEachIndexed { columnIndex, value ->
                    val b = button(value) { if (value == "CH ▲") keypadCycle(-1) else if (value == "CH ▼") keypadCycle(1) else digit(value) }
                        .apply { id = KEYPAD_BUTTON_BASE + rowIndex * 3 + columnIndex }
                    line.addView(b, weight(1f))
                }
                side.addView(line)
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("★ Favorites") { openFavorites() }.apply { id = KEYPAD_FAVORITES_ID }, weight(1f))
            actions.addView(button("↶ Recall") { recall() }.apply { id = KEYPAD_RECALL_ID }, weight(1f))
            side.addView(actions)
            side.addView(button("OK · TUNE") { if (dial.isNotEmpty()) resolveDial() else chosen?.let(::tune) }.apply { id = KEYPAD_OK_ID })
        }
        if (dial.isNotEmpty()) side.addView(label("DIRECT DIAL  #$dial\n${findDial(dial)?.name ?: "Channel not found"}", 17, AMBER, true))
        val tuneLabel = label(tuningStatus, 15, FOCUS).apply { setBackground(panel(false, 0xff132a36.toInt())); visibility = if (tuningStatus.isBlank()) View.GONE else View.VISIBLE }
        pickerTuneLabel = tuneLabel
        side.addView(tuneLabel)
        side.addView(label("◀ / ▶: move between lists\n▲ / ▼: focused list\nCH ▲ / ▼: channel step\nOK: tune · 0–9: direct dial\n229: Recall · 177: Favorites", 13, MUTED))
        body.addView(side, weight(0.25f))
        root.addView(body)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        if (focusRestoreId != View.NO_ID) {
            val focusTarget = findViewById<View>(focusRestoreId)
            if (focusTarget != null) focusTarget.requestFocus()
            else if (channels.isNotEmpty()) main.post { findViewById<View>(CHANNEL_ID_BASE + channelIndex)?.requestFocus() }
        } else if (channels.isNotEmpty()) {
            main.post { findViewById<View>(CHANNEL_ID_BASE + channelIndex)?.requestFocus() }
        }
    }

    private fun focusChannelAt(index: Int) {
        val id = CHANNEL_ID_BASE + index
        focusRestoreId = id
        main.post { findViewById<View>(id)?.requestFocus() }
    }
    private fun findDial(digits: String): ChannelDto? {
        val n = digits.toIntOrNull() ?: return null
        val favorites = genres.flatMap { it.channels }.filter { it.isFavorite }.sortedBy { it.favoriteOrder }
        val rest = genres.flatMap { it.channels }.filterNot { it.isFavorite }
        return (favorites + rest).firstOrNull { it.dummyNumber == n } ?: (favorites + rest).firstOrNull { it.redirectNumber == n }
    }
    private fun digit(d: String) {
        channelStepTask?.let(main::removeCallbacks); channelStepTask = null
        dial = (dial + d).takeLast(3)
        dialTask?.let(main::removeCallbacks)
        val completed = dial.length == 3
        dialTask = Runnable { resolveDial() }
        main.postDelayed(dialTask!!, if (completed) 350 else 3000)
        renderPicker()
    }
    private fun resolveDial() { val item = findDial(dial); dialTask?.let(main::removeCallbacks); dial = ""; if (item != null) tune(item) else renderPicker() }
    private fun stepChannel(delta: Int) {
        channelStepTask?.let(main::removeCallbacks)
        val list = currentChannels(); if (list.isEmpty()) return
        channelIndex = (channelIndex + delta + list.size) % list.size; pageIndex = channelIndex / PAGE_SIZE
        val target = list[channelIndex]
        tuningStatus = "Channel ${if (delta < 0) "Up" else "Down"}: ${target.dummyNumber} ${target.name} · tuning in 3s"
        renderPicker()
        val token = target.id
        channelStepTask = Runnable { channelStepTask = null; if (page == "picker" && currentChannels().getOrNull(channelIndex)?.id == token) tune(target, true) }
        main.postDelayed(channelStepTask!!, 3000)
    }
    private fun keypadCycle(delta: Int) {
        if (focusedArea == "genre") {
            val count = displayGenres().size
            if (count == 0) return
            val oldId = currentGenre()?.id
            if (oldId != null) selectedByGenre[oldId] = channelIndex
            genreIndex = (genreIndex + delta + count) % count
            channelIndex = (selectedByGenre[currentGenre()?.id] ?: 0).coerceIn(0, (currentChannels().size - 1).coerceAtLeast(0))
            pageIndex = channelIndex / PAGE_SIZE
            focusRestoreId = GENRE_ID_BASE + genreIndex
            renderPicker()
        } else {
            val list = currentChannels()
            if (list.isEmpty()) return
            channelIndex = (channelIndex + delta + list.size) % list.size
            pageIndex = channelIndex / PAGE_SIZE
            renderPicker()
        }
    }
    private fun handlePickerDpad(keyCode: Int): Boolean {
        val activeId = window.decorView.findFocus()?.id ?: View.NO_ID
        val genreCount = displayGenres().size
        when {
            activeId in GENRE_ID_BASE until GENRE_ID_BASE + genreCount -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    val next = (genreIndex + delta + genreCount) % genreCount
                    findViewById<View>(GENRE_ID_BASE + next)?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focusChannelAt(channelIndex); return true }
            }
            activeId in CHANNEL_ID_BASE until CHANNEL_ID_BASE + currentChannels().size -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val count = currentChannels().size
                    if (count == 0) return true
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    val next = (channelIndex + delta + count) % count
                    pageIndex = next / PAGE_SIZE
                    renderPicker()
                    focusChannelAt(next)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { findViewById<View>(GENRE_ID_BASE + genreIndex)?.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focusRestoreId = KEYPAD_TOGGLE_ID; findViewById<View>(KEYPAD_TOGGLE_ID)?.requestFocus(); return true }
            }
            activeId == KEYPAD_TOGGLE_ID && keypadOpen -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { findViewById<View>(KEYPAD_BUTTON_BASE)?.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { focusChannelAt(channelIndex); return true }
            }
            keypadOpen && activeId in KEYPAD_BUTTON_BASE..KEYPAD_OK_ID -> return moveKeypadFocus(activeId, keyCode)
        }
        return false
    }
    private fun moveKeypadFocus(activeId: Int, keyCode: Int): Boolean {
        val targetId = when {
            activeId in KEYPAD_BUTTON_BASE..(KEYPAD_BUTTON_BASE + 11) -> {
                val offset = activeId - KEYPAD_BUTTON_BASE
                val row = offset / 3
                val col = offset % 3
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (row == 0) KEYPAD_TOGGLE_ID else KEYPAD_BUTTON_BASE + (row - 1) * 3 + col
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        row < 2 -> KEYPAD_BUTTON_BASE + (row + 1) * 3 + col
                        row == 2 -> KEYPAD_BUTTON_BASE + 9 + col
                        col == 0 -> KEYPAD_FAVORITES_ID
                        col == 1 -> KEYPAD_RECALL_ID
                        else -> KEYPAD_RECALL_ID
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (col > 0) activeId - 1 else if (row == 0) CHANNEL_ID_BASE + channelIndex else CHANNEL_ID_BASE + channelIndex
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (col < 2) activeId + 1 else KEYPAD_TOGGLE_ID
                    else -> return false
                }
            }
            activeId == KEYPAD_FAVORITES_ID -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> KEYPAD_RECALL_ID
                KeyEvent.KEYCODE_DPAD_UP -> KEYPAD_BUTTON_BASE + 9
                KeyEvent.KEYCODE_DPAD_DOWN -> KEYPAD_OK_ID
                KeyEvent.KEYCODE_DPAD_LEFT -> CHANNEL_ID_BASE + channelIndex
                else -> return false
            }
            activeId == KEYPAD_RECALL_ID -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> KEYPAD_FAVORITES_ID
                KeyEvent.KEYCODE_DPAD_UP -> KEYPAD_BUTTON_BASE + 11
                KeyEvent.KEYCODE_DPAD_DOWN -> KEYPAD_OK_ID
                KeyEvent.KEYCODE_DPAD_RIGHT -> KEYPAD_TOGGLE_ID
                else -> return false
            }
            activeId == KEYPAD_OK_ID -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> KEYPAD_RECALL_ID
                KeyEvent.KEYCODE_DPAD_LEFT -> CHANNEL_ID_BASE + channelIndex
                KeyEvent.KEYCODE_DPAD_RIGHT -> KEYPAD_TOGGLE_ID
                else -> return false
            }
            else -> return false
        }
        findViewById<View>(targetId)?.requestFocus()
        return true
    }
    private fun openFavorites() {
        if (genres.flatMap { it.channels }.any { it.isFavorite }) {
            selectedByGenre[-1] = 0
            jumpGenre(0)
            focusedArea = "channels"
            focusChannelAt(0)
        }
    }
    private fun recall() {
        val id = lastTuneIds.getOrNull(1) ?: return
        val item = genres.flatMap { it.channels }.firstOrNull { it.id == id } ?: return
        genreIndex = displayGenres().indexOfFirst { it.id == item.genreId }.coerceAtLeast(0)
        channelIndex = currentChannels().indexOfFirst { it.id == id }.coerceAtLeast(0); pageIndex = channelIndex / PAGE_SIZE
        tune(item)
    }
    private fun tune(channel: ChannelDto, fast: Boolean = false) {
        if (lastTuneIds.firstOrNull() != channel.id) { lastTuneIds.add(0, channel.id); lastTuneIds = lastTuneIds.distinct().take(2).toMutableList() }
        tuningStatus = "Tuning ${channel.name} (${channel.redirectNumber})…"; renderPicker()
        io.execute {
            try {
                remote.tune(channel.redirectNumber, fast)
                main.post { lastTuned = "${channel.name} [${channel.dummyNumber}]"; tuningStatus = ""; renderPicker() }
            } catch (e: Throwable) { main.post { tuningStatus = "Tune error: ${e.message ?: "Remote unavailable"}"; renderPicker(); main.postDelayed({ tuningStatus = ""; renderPicker() }, 3000) } }
        }
    }

    private fun renderSettings() {
        if (!::store.isInitialized || page == "picker") return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(22), dp(30), dp(22)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button(if (page == "settings") "‹  PICKER" else "‹  SETTINGS") {
            if (page == "settings") { page = "picker"; renderPicker() }
            else { page = "settings"; renderSettings() }
        })
        header.addView(label(if (page == "settings") "Settings" else when (page) { "pairing" -> "Airtel box pairing"; "remote" -> "Remote Controls"; "edit" -> "Edit Channels & Genres"; "json" -> "Channel JSON"; else -> "Help & About" }, 27, WHITE, true), weight(1f))
        root.addView(header)
        when (page) {
            "settings" -> settingsHome(root)
            "pairing" -> pairingPage(root)
            "remote" -> remotePage(root)
            "edit" -> editPage(root)
            "json" -> jsonPage(root)
            else -> helpPage(root)
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun settingsHome(root: LinearLayout) {
        val state = remote.snapshot()
        root.addView(label("SETTINGS", 14, GREEN, true))
        val remoteLabel = label("Remote ${if (state.connected) "Connected to ${state.model ?: state.host}" else state.status + (state.error?.let { " · $it" } ?: "")}", 18, if (state.connected) GREEN else AMBER)
        settingsRemoteLabel = remoteLabel
        root.addView(remoteLabel)
        root.addView(button("AIRTEL BOX PAIRING & CONNECTION") { page = "pairing"; renderSettings() })
        root.addView(button("REMOTE CONTROLS") { page = "remote"; renderSettings() })
        root.addView(button("REMOTE TEXT INPUT: ${if (remote.remoteTextInputEnabled) "ON" else "OFF · GAMEPAD SAFE"}") { remote.setRemoteTextInputEnabled(!remote.remoteTextInputEnabled); renderSettings() })
        root.addView(label("Remote text input can conflict with third-party keyboard services such as tvQuickActions Gamepad. Toggle reconnects the session.", 14, MUTED))
        root.addView(button("EDIT CHANNELS & GENRES") { selectedEditGenre = 0; editFavorites = false; page = "edit"; renderSettings() })
        root.addView(button("CHANNEL JSON · IMPORT / EXPORT") { page = "json"; renderSettings() })
        root.addView(button("HELP & ABOUT") { page = "help"; renderSettings() })
    }

    private fun pairingPage(root: LinearLayout) {
        val snap = remote.snapshot()
        root.addView(label("AIRTEL BOX PAIRING", 14, GREEN, true))
        val host = editField("Airtel box IP address or host name", remote.savedHost()).also { hostField = it }
        root.addView(host)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("FIND AIRTEL BOX") { discoverBoxes() }, weight(1f))
        row.addView(button("PAIR WITH AIRTEL") { startPairing(host.text.toString()) }, weight(1f))
        row.addView(button("RECONNECT") { remote.connect(); renderSettings() }, weight(1f))
        root.addView(row)
        root.addView(label(discoveryText, 15, MUTED))
        boxes.forEach { box -> root.addView(button("${box.name} · ${box.ip}") { host.setText(box.ip); host.setSelection(host.text.length); discoveryText = "Selected ${box.name} at ${box.ip}. Choose PAIR WITH AIRTEL."; renderSettings() }) }
        val statusLabel = label(when {
            snap.status == "waiting_code" -> snap.error ?: "Airtel box is showing a pairing code. Enter it below."
            snap.connected -> "Connected to ${snap.model ?: snap.host}${snap.currentApp?.let { " · $it" } ?: ""}"
            snap.error != null -> "${snap.status}: ${snap.error}"
            else -> "Remote: ${snap.status}${snap.host.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
        }, 18, if (snap.connected) GREEN else AMBER)
        pairingStatusLabel = statusLabel
        root.addView(statusLabel)
        val code = editField("6-character code shown on Airtel TV", "").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; filters = arrayOf(android.text.InputFilter.LengthFilter(6)); codeField = this }
        root.addView(code)
        root.addView(button("CONFIRM PAIRING CODE") {
            try { remote.finishPair(code.text.toString()); code.text.clear(); showMessage("Pairing code submitted") }
            catch (e: Throwable) { showMessage(e.message ?: "Pairing failed") }
        })
        root.addView(label("Pairing credentials and the Airtel certificate fingerprint stay in private app storage.", 14, MUTED))
    }
    private fun remotePage(root: LinearLayout) {
        root.addView(label("Send commands to the paired Airtel box. Remote controls require an active connection.", 15, MUTED))
        fun key(label: String, command: String): Button = button(label) { sendRemoteKey(command) }
        val dpadTop = LinearLayout(this).apply { gravity = Gravity.CENTER }
        dpadTop.addView(key("▲", "UP"))
        root.addView(dpadTop)
        val dpadMiddle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        dpadMiddle.addView(key("◀", "LEFT")); dpadMiddle.addView(key("OK", "CENTER")); dpadMiddle.addView(key("▶", "RIGHT"))
        root.addView(dpadMiddle)
        val dpadBottom = LinearLayout(this).apply { gravity = Gravity.CENTER }
        dpadBottom.addView(key("▼", "DOWN"))
        root.addView(dpadBottom)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("HOME" to "HOME", "BACK" to "BACK", "MENU" to "MENU", "POWER" to "POWER", "TV" to "TV").forEach { (label, command) -> controls.addView(key(label, command), weight(1f)) }
        root.addView(controls)
        val volume = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("VOLUME −" to "VOLUME_DOWN", "MUTE" to "MUTE", "VOLUME +" to "VOLUME_UP").forEach { (label, command) -> volume.addView(key(label, command), weight(1f)) }
        root.addView(volume)
        root.addView(label("SEND TEXT", 15, GREEN, true))
        val textField = editField("Text to enter on Airtel box", "")
        root.addView(textField)
        root.addView(button("SEND TEXT") {
            try { remote.sendText(textField.text.toString()); showMessage("Text sent") } catch (e: Throwable) { showMessage(e.message ?: "Could not send text") }
        })
        root.addView(label("LAUNCH APP OR DEEP LINK", 15, GREEN, true))
        val appField = editField("App name, package, or deep link", "")
        root.addView(appField)
        root.addView(button("LAUNCH") {
            try { remote.launch(appField.text.toString()); showMessage("Launch request sent") } catch (e: Throwable) { showMessage(e.message ?: "Could not launch app") }
        })
    }
    private fun sendRemoteKey(command: String) {
        io.execute {
            try { remote.sendKey(command); main.post { showMessage("$command sent") } }
            catch (e: Throwable) { main.post { showMessage(e.message ?: "Remote command failed") } }
        }
    }
    private fun startPairing(host: String) {
        try { remote.startPair(host); codeField?.requestFocus(); showKeyboard(codeField); showMessage("Pairing started. Enter the code shown on the Airtel box.") }
        catch (e: Throwable) { showMessage(e.message ?: "Could not start pairing") }
    }
    private fun discoverBoxes() {
        if (activeDiscovery != null) return
        boxes = emptyList(); discoveryText = "Searching with mDNS and local network scan…"; renderSettings()
        val scan = Discovery(this) { results, done -> main.post {
            boxes = results.take(12)
            discoveryText = when { done && results.isEmpty() -> "No boxes found. Check same Wi-Fi/LAN or enter an IP manually."; done -> "Found ${results.size} device(s). Select one or enter an IP manually."; else -> "Searching…" }
            renderSettings()
        } }
        activeDiscovery = scan
        io.execute { scan.start(); activeDiscovery = null }
    }

    private fun editPage(root: LinearLayout) {
        reloadGenres()
        val favs = genres.flatMap { it.channels }.filter { it.isFavorite }.sortedBy { it.favoriteOrder }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(button("+ Add Genre") { genreForm(null) })
        head.addView(button("⭐ Favorites (${favs.size})") { editFavorites = true; renderSettings() })
        head.addView(button("Channels") { editFavorites = false; renderSettings() })
        root.addView(head)
        genres.forEachIndexed { index, genre ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(button("${if (selectedEditGenre == index) "▶ " else ""}${genre.name} (${genre.channels.size})") { selectedEditGenre = index; editFavorites = false; renderSettings() }, weight(1f))
            row.addView(button("▲") { moveGenre(index, -1) }); row.addView(button("▼") { moveGenre(index, 1) })
            row.addView(button("✎") { genreForm(genre) }); row.addView(button("✕") { confirm("Delete ${genre.name} and all its channels?") { store.deleteGenre(genre.id); selectedEditGenre = 0; renderSettings() } })
            root.addView(row)
        }
        if (editFavorites) {
            root.addView(label("⭐ Favorites · reorder or remove", 20, GREEN, true))
            favs.forEachIndexed { i, c ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("[${c.dummyNumber}] ${c.name}", 17, WHITE), weight(1f))
                row.addView(button("▲") { moveFavorite(i, -1) }); row.addView(button("▼") { moveFavorite(i, 1) })
                row.addView(button("♥") { store.toggleFavorite(c.id); reloadGenres(); renderSettings() })
                root.addView(row)
            }
        } else {
            val active = genres.getOrNull(selectedEditGenre)
            root.addView(label(active?.let { "Channels in ${it.name}" } ?: "Add a genre to start", 20, GREEN, true))
            active?.let { root.addView(button("+ Add Channel") { channelForm(it.id, null) }) }
            active?.channels?.sortedBy { it.sortOrder }?.forEachIndexed { i, c ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(button("${if (c.isFavorite) "♥" else "♡"} [${c.dummyNumber}] ${c.name} → ${c.redirectNumber}") { store.toggleFavorite(c.id); reloadGenres(); renderSettings() }, weight(1f))
                row.addView(button("▲") { moveChannel(active.id, i, -1) }); row.addView(button("▼") { moveChannel(active.id, i, 1) })
                row.addView(button("✎") { channelForm(active.id, c) }); row.addView(button("✕") { confirm("Delete ${c.name}?") { store.deleteChannel(c.id); reloadGenres(); renderSettings() } })
                root.addView(row)
            }
        }
    }
    private fun genreForm(item: GenreDto?) {
        val name = editField("Genre name", item?.name ?: "")
        val order = numberField("Order (1 = top)", item?.sortOrder?.takeIf { it > 0 } ?: if (item == null) genres.size + 1 else null)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0); addView(name); addView(order) }
        AlertDialog.Builder(this).setTitle(if (item == null) "Add Genre" else "Edit Genre").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton(if (item == null) "Save" else "Update") { _, _ ->
                try { val n = name.text.toString().trim(); val s = order.text.toString().toIntOrNull() ?: 0; if (item == null) store.createGenre(n, s) else store.updateGenre(item.id, n, s); reloadGenres(); renderSettings() }
                catch (e: Throwable) { showMessage(e.message ?: "Could not save genre") }
            }.show()
    }
    private fun channelForm(genreId: Int, item: ChannelDto?) {
        val name = editField("Channel name", item?.name ?: "")
        val dummy = numberField("Dummy display number", item?.dummyNumber)
        val redirect = numberField("Redirect STB number", item?.redirectNumber)
        val order = numberField("Sort order", item?.sortOrder ?: (genres.firstOrNull { it.id == genreId }?.channels?.size?.plus(1) ?: 1))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0); addView(name); addView(dummy); addView(redirect); addView(order) }
        AlertDialog.Builder(this).setTitle(if (item == null) "Add Channel" else "Edit Channel").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton(if (item == null) "Save Channel" else "Update Channel") { _, _ ->
                try {
                    val n = name.text.toString().trim(); val d = dummy.text.toString().toInt(); val r = redirect.text.toString().toInt(); val o = order.text.toString().toIntOrNull() ?: 0
                    if (item == null) store.createChannel(genreId, n, d, r, o) else store.updateChannel(item.id, genreId, n, d, r, o)
                    reloadGenres(); renderSettings()
                } catch (e: Throwable) { showMessage(e.message ?: "Could not save channel") }
            }.show()
    }
    private fun moveGenre(index: Int, delta: Int) {
        val list = genres.toMutableList(); val to = index + delta; if (to !in list.indices) return
        val moved = list.removeAt(index); list.add(to, moved); store.reorderGenres(list.map { it.id }); selectedEditGenre = to; reloadGenres(); renderSettings()
    }
    private fun moveChannel(genreId: Int, index: Int, delta: Int) {
        val list = genres.firstOrNull { it.id == genreId }?.channels?.sortedBy { it.sortOrder }?.toMutableList() ?: return
        val to = index + delta; if (to !in list.indices) return
        val moved = list.removeAt(index); list.add(to, moved); store.reorderChannels(genreId, list.map { it.id }); reloadGenres(); renderSettings()
    }
    private fun moveFavorite(index: Int, delta: Int) {
        val ids = genres.flatMap { it.channels }.filter { it.isFavorite }.sortedBy { it.favoriteOrder }.map { it.id }.toMutableList()
        val to = index + delta; if (to !in ids.indices) return
        val id = ids.removeAt(index); ids.add(to, id); store.reorderFavorites(ids); reloadGenres(); renderSettings()
    }

    private fun jsonPage(root: LinearLayout) {
        root.addView(label("Import, export, or edit the complete channel configuration. Pairing credentials are separate.", 15, MUTED))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("LOAD CURRENT JSON") { jsonDraft = store.exportConfigJson(); renderSettings() })
        actions.addView(button("EXPORT CURRENT JSON") { createJsonDocument() })
        actions.addView(button("CHOOSE JSON FILE") { openJsonDocument() })
        root.addView(actions)
        val editor = EditText(this).apply { setText(jsonDraft); gravity = Gravity.TOP or Gravity.START; minLines = 12; setTextColor(WHITE); setHintTextColor(MUTED); hint = "Paste or edit JSON. Expected version 1 with genres."; setBackground(panel(false)) }
        root.addView(editor, LinearLayout.LayoutParams(-1, dp(300)))
        root.addView(button("APPLY JSON") {
            jsonDraft = editor.text.toString()
            confirm("Replace current genres and channels with this JSON? This does not affect remote pairing.") {
                try { store.importConfigJson(jsonDraft); reloadGenres(); showMessage("Configuration applied successfully"); page = "json"; renderSettings() }
                catch (e: Throwable) { showMessage(e.message ?: "Invalid channel JSON") }
            }
        })
    }
    private val REQUEST_OPEN_JSON = 3031
    private val REQUEST_SAVE_JSON = 3032
    private fun openJsonDocument() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_OPEN_JSON) }
    private fun createJsonDocument() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE, "channels.json"), REQUEST_SAVE_JSON) }
    @Deprecated("Document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQUEST_OPEN_JSON -> { jsonDraft = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }; org.json.JSONObject(jsonDraft); page = "json"; renderSettings(); showMessage("JSON loaded. Review and choose Apply JSON.") }
                REQUEST_SAVE_JSON -> contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.exportConfigJson()) }
            }
        } catch (e: Throwable) { showMessage(e.message ?: "Could not read/write JSON") }
    }
    private fun helpPage(root: LinearLayout) {
        root.addView(label("PICKER NAVIGATION", 16, GREEN, true))
        root.addView(label("◀ / ▶ Move between genre and channel areas\n▲ / ▼ Move through the focused list or keypad\nCH ▼ / ▲ Next / previous channel\nOK / Enter Tune selected\n0–9 Direct dial (up to 3 digits)\nRemote key 229 Recall previous channel\nRemote key 177 Open Favorites", 18, WHITE))
        root.addView(label("Android TV Remote v2 currently sends short key presses only. Remote text input is optional and may conflict with third-party keyboard services.", 15, MUTED))
        root.addView(label("StbLab · Native Android TV app", 16, GREEN))
    }

    private fun refreshStatusViews() {
        val state = remote.snapshot()
        pickerConnectionLabel?.let {
            it.text = if (state.connected) "● LIVE" else "● OFFLINE"
            it.setTextColor(if (state.connected) GREEN else AMBER)
        }
        pickerTelemetryLabel?.text = buildString {
            appendLine("POWER    ${state.isOn?.let { if (it) "ON" else "STANDBY" } ?: "—"}")
            appendLine("VOLUME   ${state.volumeLevel?.let { "$it/${state.volumeMax}${if (state.muted == true) " MUTE" else ""}" } ?: "—"}")
            appendLine("APP      ${state.currentApp?.substringAfterLast('.') ?: "TV Home"}")
            appendLine("TUNED    ${lastTuned.ifBlank { "—" }}")
        }
        settingsRemoteLabel?.let {
            it.text = "Remote ${if (state.connected) "Connected to ${state.model ?: state.host}" else state.status + (state.error?.let { message -> " · $message" } ?: "")}"
            it.setTextColor(if (state.connected) GREEN else AMBER)
        }
        pairingStatusLabel?.let {
            it.text = when {
                state.status == "waiting_code" -> state.error ?: "Airtel box is showing a pairing code. Enter it below."
                state.connected -> "Connected to ${state.model ?: state.host}${state.currentApp?.let { app -> " · $app" } ?: ""}"
                state.error != null -> "${state.status}: ${state.error}"
                else -> "Remote: ${state.status}${state.host.takeIf { host -> host.isNotBlank() }?.let { host -> " · $host" } ?: ""}"
            }
            it.setTextColor(if (state.connected) GREEN else AMBER)
        }
    }

    private fun confirm(message: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> action() }.show() }
    private fun showMessage(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showKeyboard(field: EditText?) { field?.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) } }
    private fun numberField(hintValue: String, value: Int?): EditText = editField(hintValue, value?.toString() ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
    private fun editField(hintValue: String, value: String): EditText = EditText(this).apply {
        hint = hintValue; setText(value); setTextColor(TEXT); setHintTextColor(MUTED); setSingleLine(true); setBackground(panel(false)); setPadding(dp(12), dp(8), dp(12), dp(8))
        onFocusChangeListener = View.OnFocusChangeListener { view, focused -> view.background = panel(focused); if (focused) view.elevation = dp(4).toFloat() else view.elevation = 0f }
    }
    private fun column(title: String, ignored: Float): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)); addView(label(title, 15, ACCENT, true)) }
    private fun label(value: String, size: Int, color: Int = WHITE, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(dp(10), dp(8), dp(10), dp(8)); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true; setTextColor(TEXT); textSize = 15f
        setPadding(dp(12), dp(7), dp(12), dp(7)); background = panel(false)
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused && view.id != View.NO_ID) focusRestoreId = view.id
            view.background = panel(focused || isSelected)
            (view as? Button)?.setTextColor(if (focused || isSelected) Color.BLACK else TEXT)
            view.elevation = if (focused) dp(5).toFloat() else 0f
        }
        setOnClickListener { action() }
    }
    private fun panel(active: Boolean, idleColor: Int = SURFACE): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(5).toFloat()
        if (active) {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(0xff2fc2ff.toInt(), 0xff0ea5f7.toInt(), 0xff0089e5.toInt())
        } else setColor(idleColor)
        setStroke(dp(if (active) 2 else 1), if (active) 0xffd4e4f0.toInt() else BORDER)
    }
    private fun weight(w: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
    private fun dp(n: Int): Int = (resources.displayMetrics.density * n).toInt()

    companion object {
        private const val PAGE_SIZE = 11
        private const val GENRE_ID_BASE = 10000
        private const val CHANNEL_ID_BASE = 20000
        private const val SETTINGS_BUTTON_ID = 30001
        private const val KEYPAD_TOGGLE_ID = 30002
        private const val KEYPAD_BUTTON_BASE = 31000
        private const val KEYPAD_FAVORITES_ID = 31020
        private const val KEYPAD_RECALL_ID = 31021
        private const val KEYPAD_OK_ID = 31022
        private val BG = 0xff14151c.toInt()
        private val GENRE_SURFACE = 0xff142238.toInt()
        private val CHANNEL_SURFACE = 0xff17263a.toInt()
        private val SURFACE = 0xff1b2939.toInt()
        private val BORDER = 0xff44566d.toInt()
        private val FOCUS = 0xff32b8ff.toInt()
        private val ACCENT = 0xff159feb.toInt()
        private val GREEN = 0xff4ac26b.toInt()
        private val AMBER = 0xffff5252.toInt()
        private val TEXT = 0xfff5f7ff.toInt()
        private val DIM = 0xffd7deef.toInt()
        private val MUTED = 0xffbdc9e0.toInt()
        private val WHITE = 0xfff5f7ff.toInt()
    }
}
