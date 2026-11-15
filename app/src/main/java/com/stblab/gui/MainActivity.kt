package com.stblab.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
    private var pickerTuneLabel: TextView? = null
    private var pickerPowerValue: TextView? = null
    private var pickerVolumeValue: TextView? = null
    private var pickerAppValue: TextView? = null
    private var pickerTunedValue: TextView? = null
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
        hideSystemChrome()
        window.decorView.setBackgroundColor(BG)
        remote = RemoteV2Client.get(this)
        store = ChannelStore(this)
        reloadGenres()
        renderPicker()
        main.postDelayed(poll, 3000)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemChrome() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemChrome()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (page == "picker" && event.action == KeyEvent.ACTION_DOWN) {
            val isChannelStep = event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || event.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
                event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN
            if (!isChannelStep && channelStepTask != null) {
                tuningStatus = ""
                channelStepTask?.let(main::removeCallbacks)
                channelStepTask = null
                pickerTuneLabel?.visibility = View.GONE
            }
            if (event.repeatCount == 0) {
                val pressedDigit = digitFor(event)
                if (pressedDigit != null) { digit(pressedDigit); return true }
            }
            if (event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT && handlePickerDpad(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun digitFor(event: KeyEvent): String? {
        val code = event.keyCode
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return (code - KeyEvent.KEYCODE_0).toString()
        if (code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) return (code - KeyEvent.KEYCODE_NUMPAD_0).toString()
        val character = event.unicodeChar
        return character.takeIf { it in '0'.code..'9'.code }?.toChar()?.toString()
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
        pickerTuneLabel = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            clipChildren = false
            clipToPadding = false
        }

        // The guide occupies 75% of the screen; the utility sidebar occupies 25%.
        val guide = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xbd02051a.toInt())
            clipChildren = false
            clipToPadding = false
        }

        val genreColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xbd02051a.toInt())
        }
        val genreHeader = label("GENRE", 24, ACCENT, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xb302051c.toInt())
        }
        genreColumn.addView(genreHeader, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val genreList = fixedRows().apply { setPadding(dp(8), dp(5), dp(8), dp(5)) }
        val visibleGenres = displayGenres()
        visibleGenres.forEachIndexed { index, genre ->
            genreList.addView(makeGenreRow(genre, index), LinearLayout.LayoutParams(-1, -1))
        }
        genreColumn.addView(genreList, LinearLayout.LayoutParams(-1, 0, 1f))
        guide.addView(genreColumn, LinearLayout.LayoutParams(0, -1, 0.32f))

        guide.addView(View(this).apply { setBackgroundColor(0x8cdcefff.toInt()) }, LinearLayout.LayoutParams(dp(1), -1))

        val channelColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xbd02051a.toInt())
        }
        val channels = currentChannels()
        val pageCount = ((channels.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xb302051c.toInt())
        }
        val pageNav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pageNav.addView(pageArrow("◀", enabled = pageCount > 1) {
            pageIndex = (pageIndex - 1 + pageCount) % pageCount
            channelIndex = pageIndex * PAGE_SIZE
            selectedByGenre[currentGenre()?.id ?: 0] = channelIndex
            focusRestoreId = CHANNEL_ID_BASE + channelIndex
            focusedArea = "channels"
            renderPicker()
        })
        val pagePill = label("Page ${pageIndex + 1} of $pageCount", 13, DIM).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x59000000) }
        }
        pageNav.addView(pagePill)
        pageNav.addView(pageArrow("▶", enabled = pageCount > 1) {
            pageIndex = (pageIndex + 1) % pageCount
            channelIndex = pageIndex * PAGE_SIZE
            selectedByGenre[currentGenre()?.id ?: 0] = channelIndex
            focusRestoreId = CHANNEL_ID_BASE + channelIndex
            focusedArea = "channels"
            renderPicker()
        })
        pageHeader.addView(pageNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val genreTitle = label(currentGenre()?.name ?: "Channels", 24, WHITE, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        }
        pageHeader.addView(genreTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val countPill = label("${channels.size} ch", 13, DIM).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x59000000) }
        }
        pageHeader.addView(countPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        channelColumn.addView(pageHeader, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val channelList = fixedRows().apply { setPadding(dp(8), dp(5), dp(8), dp(5)) }
        val pageStart = pageIndex * PAGE_SIZE
        channels.drop(pageStart).take(PAGE_SIZE).forEachIndexed { offset, channel ->
            channelList.addView(makeChannelRow(channel, pageStart + offset), LinearLayout.LayoutParams(-1, -1))
        }
        if (channels.isEmpty()) {
            channelList.addView(label("No channels found in this genre.", 20, DIM).apply {
                gravity = Gravity.CENTER
                setPadding(dp(30), dp(30), dp(30), dp(30))
            }, LinearLayout.LayoutParams(-1, -1))
        }
        channelColumn.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1f))
        guide.addView(channelColumn, LinearLayout.LayoutParams(0, -1, 0.68f))
        root.addView(guide, LinearLayout.LayoutParams(0, -1, 3f))
        root.addView(View(this).apply { setBackgroundColor(0xaddcefff.toInt()) }, LinearLayout.LayoutParams(dp(2), -1))

        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xbd02051a.toInt())
        }
        val sidebarScroll = ScrollView(this).apply { isFillViewport = false; clipChildren = false; clipToPadding = false }
        val sidebarContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleAndPad = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleAndPad.addView(label("StbLab Picker", 18, TEXT, true).apply {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setPadding(0, 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = remote.snapshot()
        val toggle = compactButton(if (keypadOpen) "🔢 Hide Pad" else "🔢 NumPad", KEYPAD_TOGGLE_ID) {
            keypadOpen = !keypadOpen
            focusRestoreId = KEYPAD_TOGGLE_ID
            renderPicker()
        }.apply {
            isSelected = keypadOpen
            setBackground(if (keypadOpen) solidPanel(FOCUS, FOCUS, 1) else solidPanel(0xff1a293a.toInt(), 0xff52647b.toInt(), 1))
            setTextColor(if (keypadOpen) 0xff071820.toInt() else TEXT)
        }
        titleAndPad.addView(toggle)
        brand.addView(titleAndPad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val connection = label(if (state.connected) "● LIVE" else "● OFFLINE", 12, if (state.connected) GREEN else AMBER, true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        pickerConnectionLabel = connection
        brand.addView(connection)
        sidebarContent.addView(brand)
        sidebarContent.addView(View(this).apply { setBackgroundColor(0x6bd1e1ff.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

        if (keypadOpen) addVirtualKeypad(sidebarContent, channels.getOrNull(channelIndex))

        val telemetryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = solidPanel(0xc7020519.toInt(), 0x77d6e5ff, 1, dp(6))
        }
        val telemetryRows = listOf(
            "POWER" to (state.isOn?.let { if (it) "ON" else "STANDBY" } ?: "—"),
            "VOLUME" to (state.volumeLevel?.let { "$it/${state.volumeMax}${if (state.muted == true) " (MUTE)" else ""}" } ?: "—"),
            "APP" to (state.currentApp?.substringAfterLast('.') ?: "TV Home"),
            "TUNED" to lastTuned.ifBlank { "—" },
        )
        telemetryRows.forEach { (name, value) ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(label(name, 13, DIM).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(3), dp(8), dp(3)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val valueLabel = label(value, 13, if (name == "TUNED") FOCUS else TEXT, name == "TUNED").apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, dp(3))
            }
            when (name) {
                "POWER" -> pickerPowerValue = valueLabel
                "VOLUME" -> pickerVolumeValue = valueLabel
                "APP" -> pickerAppValue = valueLabel
                "TUNED" -> pickerTunedValue = valueLabel
            }
            line.addView(valueLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            telemetryCard.addView(line)
        }
        sidebarContent.addView(telemetryCard, sidebarGapParams())

        if (dial.isNotEmpty()) sidebarContent.addView(makeDialCard(), sidebarGapParams())
        if (tuningStatus.isNotBlank()) {
            val status = label(tuningStatus, 13, FOCUS).apply {
                typeface = Typeface.MONOSPACE
                background = solidPanel(0xc7242217.toInt(), FOCUS, 1, dp(4))
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            pickerTuneLabel = status
            sidebarContent.addView(status, sidebarGapParams())
        }

        val hints = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = solidPanel(0x05000000, 0x77d6e5ff, 1, dp(6))
        }
        listOf(
            "◀ / ▶  Move between guide and channel actions",
            "▲ / ▼  Move through the focused list or keypad",
            "CH ▼ / ▲  Next / previous channel",
            "OK (Enter)  Tune selected",
            "0–9  Direct dial (within 3s)",
            "229  Recall previous channel",
            "177  Open Favorites",
            "170  Live TV",
        ).forEach { hint -> hints.addView(label(hint, 13, DIM).apply { setPadding(0, dp(2), 0, dp(2)) }) }
        hints.addView(label("Use TVQuickActions to map the advanced remote keys.", 11, MUTED).apply {
            setPadding(0, dp(7), 0, 0)
        })
        sidebarContent.addView(hints, sidebarGapParams())

        sidebarScroll.addView(sidebarContent)
        sidebar.addView(sidebarScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        footer.addView(button("⚙  SETTINGS") {
            focusRestoreId = SETTINGS_BUTTON_ID
            page = "settings"
            renderSettings()
        }.apply { id = SETTINGS_BUTTON_ID })
        sidebar.addView(footer)
        root.addView(sidebar, LinearLayout.LayoutParams(0, -1, 1f))

        setContentView(root)
        val desiredFocus = focusRestoreId
        main.post {
            val restored = if (desiredFocus != View.NO_ID) findViewById<View>(desiredFocus)?.requestFocus() == true else false
            if (!restored && channels.isNotEmpty()) {
                focusRestoreId = CHANNEL_ID_BASE + channelIndex
                findViewById<View>(focusRestoreId)?.requestFocus()
            } else if (!restored && visibleGenres.isNotEmpty()) {
                focusRestoreId = GENRE_ID_BASE + genreIndex
                findViewById<View>(focusRestoreId)?.requestFocus()
            }
        }
    }

    private fun makeGenreRow(genre: GenreDto, index: Int): LinearLayout {
        val selected = index == genreIndex
        val row = LinearLayout(this).apply {
            id = GENRE_ID_BASE + index
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setBackground(pickerPanel(selected, GENRE_SURFACE))
        }
        val stripe = View(this).apply { setBackgroundColor(if (selected) 0xffd9e6f1.toInt() else Color.TRANSPARENT) }
        row.addView(stripe, LinearLayout.LayoutParams(dp(6), -1))
        val title = label(genre.name, 20, if (selected) Color.BLACK else WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(6), 0)
        }
        row.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        val count = label(genre.channels.size.toString(), 14, if (selected) Color.BLACK else WHITE).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, dp(14), 0)
            alpha = if (selected) 1f else 0.7f
        }
        row.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -1))
        row.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused && index != genreIndex) {
                activateGenre(index, keepGenreFocus = true)
                return@OnFocusChangeListener
            }
            if (focused) {
                focusedArea = "genre"
                focusRestoreId = view.id
            }
            view.background = pickerPanel(focused || index == genreIndex, GENRE_SURFACE, focused && focusedArea == "genre")
            stripe.setBackgroundColor(if (focused || index == genreIndex) 0xffd9e6f1.toInt() else Color.TRANSPARENT)
            val highlighted = focused || index == genreIndex
            title.setTextColor(if (highlighted) Color.BLACK else WHITE)
            count.setTextColor(if (highlighted) Color.BLACK else WHITE)
            count.alpha = if (highlighted) 1f else 0.7f
            view.elevation = if (focused) dp(6).toFloat() else 0f
        }
        row.setOnClickListener { activateGenre(index, keepGenreFocus = false) }
        return row
    }

    private fun activateGenre(index: Int, keepGenreFocus: Boolean) {
        val previousId = currentGenre()?.id
        if (previousId != null) selectedByGenre[previousId] = channelIndex
        genreIndex = index.coerceIn(0, (displayGenres().size - 1).coerceAtLeast(0))
        val targetId = currentGenre()?.id
        channelIndex = (targetId?.let { selectedByGenre[it] } ?: 0).coerceIn(0, (currentChannels().size - 1).coerceAtLeast(0))
        pageIndex = channelIndex / PAGE_SIZE
        focusedArea = if (keepGenreFocus) "genre" else "channels"
        focusRestoreId = if (keepGenreFocus) GENRE_ID_BASE + genreIndex else CHANNEL_ID_BASE + channelIndex
        renderPicker()
    }

    private fun addVirtualKeypad(parent: LinearLayout, activeChannel: ChannelDto?) {
        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = solidPanel(0xff131e2b.toInt(), 0xff3a4a60.toInt(), 1, dp(8))
        }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = solidPanel(0x59000000, 0x10ffffff, 1, dp(4))
        }
        status.addView(label("FOCUS: ${focusedArea.uppercase()}", 10, ACCENT, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(5), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status.addView(label(if (focusedArea == "channels") "● SELECTED" else "GENRE", 10, if (focusedArea == "channels") GREEN else DIM, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(5), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val target = if (dial.isNotEmpty()) "#$dial" else activeChannel?.let { "#${it.dummyNumber} ${it.name}" } ?: currentGenre()?.name.orEmpty()
        status.addView(label(target, 10, MUTED).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keypad.addView(status)

        val values = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "CH ▲", "0", "CH ▼")
        values.chunked(3).forEachIndexed { rowIndex, rowValues ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowValues.forEachIndexed { columnIndex, value ->
                val id = KEYPAD_BUTTON_BASE + rowIndex * 3 + columnIndex
                val control = keypadButton(value, id) {
                    when (value) {
                        "CH ▲" -> keypadCycle(-1)
                        "CH ▼" -> keypadCycle(1)
                        else -> digit(value)
                    }
                }
                line.addView(control, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            keypad.addView(line)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(keypadButton("★ Favorites", KEYPAD_FAVORITES_ID) { openFavorites() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        actions.addView(keypadButton("↶ Recall", KEYPAD_RECALL_ID) { recall() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        keypad.addView(actions)
        keypad.addView(keypadButton("OK  ·  ${if (dial.isNotEmpty()) "TUNE #$dial" else "TUNE SELECTED"}", KEYPAD_OK_ID) {
            if (dial.isNotEmpty()) resolveDial() else activeChannel?.let(::tune)
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        parent.addView(keypad, sidebarGapParams())
    }

    private fun makeDialCard(): LinearLayout {
        val dialCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = solidPanel(0xff132a36.toInt(), FOCUS, 2, dp(6))
        }
        dialCard.addView(label("DIRECT CHANNEL DIAL", 12, FOCUS).apply {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        dialCard.addView(label(dial, 52, FOCUS, true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        dialCard.addView(label(findDial(dial)?.name ?: "Channel not found", 16, TEXT, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return dialCard
    }

    private fun pageArrow(text: String, enabled: Boolean, action: () -> Unit): TextView = label(text, 11, if (enabled) TEXT else 0x668f9aad, true).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        alpha = if (enabled) 1f else 0.35f
        setBackground(solidPanel(0xff172536.toInt(), 0xff52647b.toInt(), 1, dp(6)))
        isClickable = enabled
        isFocusable = false
        setOnClickListener { if (enabled) action() }
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { if (text == "◀") marginEnd = dp(6) else marginStart = dp(6) }
    }

    private fun compactButton(text: String, idValue: Int, action: () -> Unit): Button = Button(this).apply {
        id = idValue
        text.let { setText(it) }
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        minWidth = 0
        minHeight = dp(30)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { action() }
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused) { focusedArea = "sidebar"; focusRestoreId = view.id }
            view.background = buttonPanel(focused || isSelected, focused)
            (view as? Button)?.setTextColor(if (focused || isSelected) Color.BLACK else TEXT)
            view.elevation = if (focused) dp(5).toFloat() else 0f
        }
    }

    private fun keypadButton(text: String, idValue: Int, action: () -> Unit): Button = compactButton(text, idValue, action).apply {
        textSize = if (idValue == KEYPAD_OK_ID) 13f else 15f
        typeface = Typeface.MONOSPACE
        background = solidPanel(0xff1b2939.toInt(), 0xff44566d.toInt(), 1, dp(5))
        setTextColor(TEXT)
    }

    private fun solidPanel(color: Int, stroke: Int, strokeDp: Int, radiusDp: Int = 5): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        setStroke(dp(strokeDp), stroke)
    }

    private fun sidebarGapParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(14)
    }
    private fun focusChannelAt(index: Int) {
        val id = CHANNEL_ID_BASE + index
        focusRestoreId = id
        main.post { findViewById<View>(id)?.requestFocus() }
    }

    private fun makeChannelRow(channel: ChannelDto, index: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            id = CHANNEL_ID_BASE + index
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(20), 0, dp(20), 0)
            contentDescription = "${channel.name}, channel ${channel.dummyNumber}, redirects to ${channel.redirectNumber}. Press OK to tune."
            setBackground(pickerPanel(index == channelIndex, CHANNEL_SURFACE))
        }
        val number = label(channel.dummyNumber.toString(), 19, if (index == channelIndex) Color.BLACK else WHITE, true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(0, 0, 0, 0)
        }
        row.addView(number, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.MATCH_PARENT))
        val name = label(channel.name, 21, if (index == channelIndex) Color.BLACK else WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(0, 0, dp(12), 0)
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val redirect = label("→ ${channel.redirectNumber}", 14, if (index == channelIndex) Color.BLACK else WHITE).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = solidPanel(0x40000000, Color.TRANSPARENT, 0, dp(4))
        }
        row.addView(redirect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused) {
                val previous = channelIndex
                if (previous != index) findViewById<View>(CHANNEL_ID_BASE + previous)?.let { old ->
                    old.setBackground(pickerPanel(false, CHANNEL_SURFACE))
                    old.elevation = 0f
                    old.scaleX = 1f
                    old.scaleY = 1f
                    (old as? LinearLayout)?.let { oldRow ->
                        (oldRow.getChildAt(0) as? TextView)?.setTextColor(WHITE)
                        (oldRow.getChildAt(1) as? TextView)?.setTextColor(WHITE)
                        (oldRow.getChildAt(2) as? TextView)?.setTextColor(WHITE)
                        (oldRow.getChildAt(2) as? TextView)?.background = solidPanel(0x40000000, Color.TRANSPARENT, 0, dp(4))
                    }
                }
                focusedArea = "channels"
                channelIndex = index
                selectedByGenre[currentGenre()?.id ?: 0] = index
                pageIndex = index / PAGE_SIZE
                focusRestoreId = view.id
            }
            val selected = focused || index == channelIndex
            view.background = pickerPanel(selected, CHANNEL_SURFACE, focused && focusedArea == "channels")
            view.elevation = if (selected || focused) dp(4).toFloat() else 0f
            number.setTextColor(if (selected) Color.BLACK else WHITE)
            name.setTextColor(if (selected) Color.BLACK else WHITE)
            redirect.setTextColor(if (selected) Color.BLACK else WHITE)
            redirect.background = solidPanel(if (selected) 0x57ffffff else 0x40000000, Color.TRANSPARENT, 0, dp(4))
            if (focused) {
                focusedArea = "channels"
                focusRestoreId = view.id
            }
        }
        row.setOnClickListener {
            channelIndex = index
            selectedByGenre[currentGenre()?.id ?: 0] = index
            tune(channel)
        }
        return row
    }
    private fun findDial(digits: String): ChannelDto? {
        val n = digits.toIntOrNull() ?: return null
        val favorites = genres.flatMap { it.channels }.filter { it.isFavorite }.sortedBy { it.favoriteOrder }
        val rest = genres.flatMap { it.channels }.filterNot { it.isFavorite }
        return (favorites + rest).firstOrNull { it.dummyNumber == n } ?: (favorites + rest).firstOrNull { it.redirectNumber == n }
    }
    private fun digit(d: String) {
        if (channelStepTask != null) tuningStatus = ""
        channelStepTask?.let(main::removeCallbacks); channelStepTask = null
        dial = (dial + d).takeLast(3)
        dialTask?.let(main::removeCallbacks)
        val completed = dial.length == 3
        dialTask = Runnable { resolveDial() }
        main.postDelayed(dialTask!!, if (completed) 350 else 3000)
        renderPicker()
    }
    private fun resolveDial() {
        val item = findDial(dial)
        dialTask?.let(main::removeCallbacks)
        dialTask = null
        dial = ""
        if (item == null) { renderPicker(); return }
        val targetGenre = displayGenres().indexOfFirst { genre -> genre.channels.any { it.id == item.id } }
        if (targetGenre >= 0) {
            val oldGenreId = currentGenre()?.id
            if (oldGenreId != null) selectedByGenre[oldGenreId] = channelIndex
            genreIndex = targetGenre
            channelIndex = currentChannels().indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            selectedByGenre[currentGenre()?.id ?: 0] = channelIndex
            pageIndex = channelIndex / PAGE_SIZE
            focusedArea = "channels"
            focusRestoreId = CHANNEL_ID_BASE + channelIndex
        }
        tune(item)
    }
    private fun stepChannel(delta: Int) {
        channelStepTask?.let(main::removeCallbacks)
        val list = currentChannels(); if (list.isEmpty()) return
        channelIndex = (channelIndex + delta + list.size) % list.size; pageIndex = channelIndex / PAGE_SIZE
        selectedByGenre[currentGenre()?.id ?: 0] = channelIndex
        focusedArea = "channels"
        focusRestoreId = CHANNEL_ID_BASE + channelIndex
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
            selectedByGenre[currentGenre()?.id ?: 0] = channelIndex
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
                    channelIndex = next
                    selectedByGenre[currentGenre()?.id ?: 0] = next
                    pageIndex = next / PAGE_SIZE
                    focusedArea = "channels"
                    focusRestoreId = CHANNEL_ID_BASE + next
                    renderPicker()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { findViewById<View>(GENRE_ID_BASE + genreIndex)?.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focusRestoreId = KEYPAD_TOGGLE_ID; findViewById<View>(KEYPAD_TOGGLE_ID)?.requestFocus(); return true }
            }
            activeId == KEYPAD_TOGGLE_ID -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    findViewById<View>(if (keypadOpen) KEYPAD_BUTTON_BASE else SETTINGS_BUTTON_ID)?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { focusChannelAt(channelIndex); return true }
            }
            activeId == SETTINGS_BUTTON_ID -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { findViewById<View>(if (keypadOpen) KEYPAD_OK_ID else KEYPAD_TOGGLE_ID)?.requestFocus(); return true }
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
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (col < 2) activeId + 1 else return true
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
                KeyEvent.KEYCODE_DPAD_UP -> KEYPAD_BUTTON_BASE + 10
                KeyEvent.KEYCODE_DPAD_DOWN -> KEYPAD_OK_ID
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                else -> return false
            }
            activeId == KEYPAD_OK_ID -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> KEYPAD_RECALL_ID
                KeyEvent.KEYCODE_DPAD_LEFT -> CHANNEL_ID_BASE + channelIndex
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
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
        focusedArea = "channels"
        focusRestoreId = CHANNEL_ID_BASE + channelIndex
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
        pickerPowerValue?.text = state.isOn?.let { if (it) "ON" else "STANDBY" } ?: "—"
        pickerVolumeValue?.text = state.volumeLevel?.let { "$it/${state.volumeMax}${if (state.muted == true) " (MUTE)" else ""}" } ?: "—"
        pickerAppValue?.text = state.currentApp?.substringAfterLast('.') ?: "TV Home"
        pickerTunedValue?.text = lastTuned.ifBlank { "—" }
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
    private fun label(value: String, size: Int, color: Int = WHITE, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(dp(10), dp(8), dp(10), dp(8)); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true; setTextColor(TEXT); textSize = 15f
        setPadding(dp(12), dp(7), dp(12), dp(7)); background = panel(false)
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused && view.id != View.NO_ID) {
                focusRestoreId = view.id
                if (page == "picker") focusedArea = "sidebar"
            }
            view.background = buttonPanel(focused || isSelected, focused)
            (view as? Button)?.setTextColor(if (focused || isSelected) Color.BLACK else TEXT)
            view.elevation = if (focused) dp(10).toFloat() else 0f
            view.scaleX = if (focused) 1.015f else 1f
            view.scaleY = if (focused) 1.015f else 1f
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
    private fun pickerPanel(active: Boolean, idleColor: Int, focused: Boolean = false): Drawable {
        val fill = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            if (active) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(0xfa2fc2ff.toInt(), 0xf70ea5f7.toInt(), 0xf50089e5.toInt())
            } else setColor(0x9401051a.toInt())
            setStroke(dp(1), if (active) 0xf2caf0ff.toInt() else 0x80d5e6ff.toInt())
        }
        if (!focused) return fill
        return LayerDrawable(arrayOf(fill, SelectorRingDrawable(dp(5).toFloat(), dp(4).toFloat())))
    }
    private fun buttonPanel(active: Boolean, focused: Boolean): Drawable {
        if (!focused) return panel(active)
        return GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(0xfffff59d.toInt(), 0xffffd43b.toInt(), 0xffff9f1c.toInt())
            setStroke(dp(3), Color.WHITE)
        }
    }
    private fun fixedRows(): LinearLayout = object : LinearLayout(this) {
        init { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (childCount == 1 && getChildAt(0).id == View.NO_ID) return
            val slotHeight = ((measuredHeight - paddingTop - paddingBottom) / PAGE_SIZE).coerceAtLeast(1)
            var changed = false
            for (index in 0 until childCount) {
                val params = getChildAt(index).layoutParams as? LinearLayout.LayoutParams ?: continue
                if (params.height != slotHeight || params.weight != 0f) {
                    params.height = slotHeight
                    params.weight = 0f
                    getChildAt(index).layoutParams = params
                    changed = true
                }
            }
            if (changed) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
    private inner class SelectorRingDrawable(
        private val radius: Float,
        private val strokeWidth: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.strokeWidth = this@SelectorRingDrawable.strokeWidth }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = strokeWidth / 2f
            paint.shader = LinearGradient(
                b.left.toFloat(), b.bottom.toFloat(), b.right.toFloat(), b.top.toFloat(),
                intArrayOf(0xffff7777.toInt(), 0xffed1b36.toInt(), 0xffa90024.toInt()),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), radius, radius, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
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
