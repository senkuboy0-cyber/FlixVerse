package com.streamverse.setting

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.streamverse.PlaylistFetcher
import com.streamverse.PlaylistSource
import java.io.ByteArrayOutputStream

/**
 * StreamVerse playlist manager, one full-screen screen built entirely in code
 * (no XML, no emoji). Lets the user:
 *  - add a new playlist by extension name + playlist URL, or by picking an
 *    M3U / M3U8 file from the device,
 *  - review and remove saved playlists,
 *  - apply everything with "Save & Reset": the app restarts and every saved
 *    playlist becomes its own separate provider, so it never mixes with the
 *    curated StreamVerse categories.
 *
 * The palette and view helpers live in SettingsUi.kt, icons are drawn with
 * Canvas (SettingsIcons.kt). All focusable views have a visible focus ring so the
 * screen works with a TV remote.
 */
class SettingsDialog : DialogFragment() {

    companion object {
        private const val PICK_FILE_REQUEST = 1001
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }

    private val saved = mutableListOf<PlaylistStore.Item>()
    private val removedLocalPaths = mutableListOf<String>()
    private var pickedFile: Pair<String, String>? = null // display name to playlist text
    private var dirty = false

    private lateinit var listBox: LinearLayout
    private lateinit var emptyBox: View
    private lateinit var counter: TextView
    private lateinit var saveButton: View
    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var pickedRow: LinearLayout
    private lateinit var pickedName: TextView
    private lateinit var urlBox: View

    private fun dp(v: Int) = requireContext().dp(v)
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        requireContext().svText(s, size, color, bold)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    /** Full-screen window, nothing dimmed: the screen paints its own background. */
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val dm = ctx.resources.displayMetrics
        val contentWidth = minOf(ctx.dp(640), (dm.widthPixels * 0.96f).toInt())

        saved.clear()
        saved.addAll(PlaylistStore.load(ctx.applicationContext))

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(toolbar(ctx))
        column.addView(scrollContent(ctx), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        column.addView(bottomBar(ctx))

        return FrameLayout(ctx).apply {
            background = ColorDrawable(StreamVerseTheme.BG)
            addView(column, FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
    }

    // ===================== Scroll content =====================

    private fun scrollContent(ctx: Context): View {
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        column.addView(sectionLabel(ctx, "ADD PLAYLIST"))
        column.addView(addCard(ctx))
        column.addView(sectionLabel(ctx, "SAVED PLAYLISTS"))
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(listBox)
        emptyBox = emptyState(ctx)
        column.addView(emptyBox)
        column.addView(infoCard(ctx))
        scroll.addView(column)

        rebuildRows()
        return scroll
    }

    private fun sectionLabel(ctx: Context, label: String): View =
        FrameLayout(ctx).apply {
            setPadding(dp(16), dp(18), dp(16), dp(6))
            addView(text(label, 12f, StreamVerseTheme.SUBTLE, true).apply { letterSpacing = 0.12f })
        }

    // ===================== Toolbar =====================

    private fun toolbar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(10))
            background = ColorDrawable(StreamVerseTheme.BG)
        }
        bar.addView(
            iconButton(SvIconDrawable.Kind.BACK, StreamVerseTheme.SUBTLE, "Back") { dismiss() },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(text("StreamVerse", 19f, StreamVerseTheme.TEXT, true))
        titles.addView(text("IPTV PLAYLIST MANAGER", 10f, StreamVerseTheme.FAINT).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(2), 0, 0)
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        counter = text("", 12f, StreamVerseTheme.FAINT, true)
        bar.addView(counter)
        return bar
    }

    // ===================== Add playlist card =====================

    private fun addCard(ctx: Context): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.svShape(StreamVerseTheme.SURFACE, 18, 1, StreamVerseTheme.STROKE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), 0, dp(12), dp(4)) }
        }

        card.addView(fieldLabel(ctx, "Extension name"))
        nameField = makeField(ctx, "My Playlist", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        card.addView(wrapField(ctx, nameField))
        card.addView(fieldLabel(ctx, "Playlist URL", topPad = true))
        urlField = makeField(ctx, "https://example.com/playlist.m3u", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        urlBox = wrapField(ctx, urlField)
        card.addView(urlBox)

        card.addView(fieldLabel(ctx, "Or load from device (M3U / M3U8)", topPad = true))
        pickedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(pickedRow)
        refreshPickedRow()

        card.addView(
            text("Add Playlist", 14f, Color.WHITE, true).apply {
                setPadding(dp(20), dp(12), dp(20), dp(12))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = ctx.accentBg()
                contentDescription = "Add playlist"
                setOnClickListener { addPlaylist() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) },
        )
        return card
    }

    private fun fieldLabel(ctx: Context, label: String, topPad: Boolean = false): View =
        text(label, 12f, StreamVerseTheme.SUBTLE, true).apply {
            setPadding(dp(4), if (topPad) dp(12) else dp(2), 0, dp(4))
        }

    private fun wrapField(ctx: Context, field: EditText): View =
        FrameLayout(ctx).apply {
            background = ctx.fieldBg(false)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            addView(field)
        }

    private fun makeField(ctx: Context, hint: String, inputType: Int): EditText =
        EditText(ctx).apply {
            setBackgroundDrawable(null)
            setSingleLine()
            this.inputType = inputType
            this.hint = hint
            setHintTextColor(StreamVerseTheme.FAINT)
            setTextColor(StreamVerseTheme.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(11), 0, dp(11))
            setOnFocusChangeListener { v, hasFocus ->
                (v.parent as? View)?.background = ctx.fieldBg(hasFocus)
            }
        }

    /** "Or load from device" row: a pick button at rest, or the picked file with an un-pick cross. */
    private fun refreshPickedRow() {
        val ctx = requireContext()
        pickedRow.removeAllViews()

        val pickButton = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ctx.ghost(12)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            contentDescription = "Pick playlist file from device"
            addView(ImageView(ctx).apply {
                setImageDrawable(SvIconDrawable(SvIconDrawable.Kind.FILE, StreamVerseTheme.ACCENT_HI))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(text("Pick M3U / M3U8 file", 14f, StreamVerseTheme.TEXT, true).apply {
                setPadding(dp(10), 0, 0, 0)
            })
            setOnClickListener { openFilePicker() }
        }

        val file = pickedFile
        if (file == null) {
            pickedRow.addView(pickButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            urlBox.alpha = 1f
            urlField.isEnabled = true
            return
        }

        // A file is loaded: show its name, allow clearing it, and dim the URL field.
        pickedRow.addView(pickButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pickedName = text(file.first, 12f, StreamVerseTheme.OK, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(10), 0, dp(6), 0)
        }
        pickedRow.addView(pickedName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        pickedRow.addView(
            ImageView(ctx).apply {
                setImageDrawable(SvIconDrawable(SvIconDrawable.Kind.CLOSE, StreamVerseTheme.SUBTLE))
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                background = ctx.ghost(12)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "Clear picked file"
                setOnClickListener {
                    pickedFile = null
                    refreshPickedRow()
                }
            },
            LinearLayout.LayoutParams(dp(34), dp(34)),
        )
        urlBox.alpha = 0.45f
        urlField.isEnabled = false
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Pick playlist file"), PICK_FILE_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILE_REQUEST) return
        val uri: Uri? = data?.data
        if (uri == null || resultCode != android.app.Activity.RESULT_OK) return

        val text = readTextCapped(uri)
        if (text.isNullOrBlank()) {
            alert("Could not read file", "The picked file could not be read as text.")
            return
        }
        if (!text.contains("#EXT") && !text.contains("://")) {
            alert("Not a playlist", "The picked file does not look like an M3U / M3U8 playlist.")
            return
        }
        val display = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "playlist.m3u"
        pickedFile = display to text
        refreshPickedRow()
        markDirty()
    }

    private fun readTextCapped(uri: Uri): String? = try {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                total += n
                if (total > MAX_FILE_BYTES) return null
                buf.write(chunk, 0, n)
            }
            buf.toString("UTF-8")
        }
    } catch (_: Exception) {
        null
    }

    // ===================== Saved playlist rows =====================

    private fun emptyState(ctx: Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        box.addView(ImageView(ctx).apply {
            setImageDrawable(SvIconDrawable(SvIconDrawable.Kind.PLAYLIST, StreamVerseTheme.STROKE))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(text("No playlists saved yet", 15f, StreamVerseTheme.SUBTLE, true).apply { setPadding(0, dp(14), 0, 0) })
        box.addView(text("Add one above with a name and a URL or file", 12f, StreamVerseTheme.FAINT).apply { setPadding(0, dp(3), 0, 0) })
        return box
    }

    private fun infoCard(ctx: Context): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.svShape(StreamVerseTheme.SURFACE, 14, 1, StreamVerseTheme.STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(16)) }
        }
        card.addView(ImageView(ctx).apply {
            setImageDrawable(SvIconDrawable(SvIconDrawable.Kind.INFO, StreamVerseTheme.ACCENT_HI))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        card.addView(text(
            "Each added playlist becomes its own separate provider — it never mixes with the StreamVerse categories. Use Save & Reset to apply; removed playlists fully disappear after an app restart.",
            12f, StreamVerseTheme.SUBTLE
        ).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun uniqueName(base: String): String {
        if (saved.none { it.name.equals(base, ignoreCase = true) }) return base
        var i = 2
        while (saved.any { it.name.equals("$base ($i)", ignoreCase = true) }) i++
        return "$base ($i)"
    }

    private fun makeRow(item: PlaylistStore.Item): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.svShape(StreamVerseTheme.SURFACE, 16, 1, StreamVerseTheme.STROKE)
            setPadding(dp(12), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), 0, dp(12), dp(8)) }
            isFocusable = true
        }

        row.addView(ImageView(ctx).apply {
            setImageDrawable(SvIconDrawable(SvIconDrawable.Kind.PLAYLIST, StreamVerseTheme.ACCENT_HI))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(26), dp(26)))

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        texts.addView(text(item.name, 15f, StreamVerseTheme.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = if (item.localPath != null) {
            "Local file · ${item.localPath.substringAfterLast('/')}"
        } else {
            item.url
        }
        texts.addView(text(sub, 11f, StreamVerseTheme.FAINT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            iconButton(SvIconDrawable.Kind.TRASH, StreamVerseTheme.DANGER, "Remove playlist") { confirmRemove(item) },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        return row
    }

    private fun rebuildRows() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        saved.forEach { listBox.addView(makeRow(it)) }
        emptyBox.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
        counter.text = "${saved.size} playlist${if (saved.size == 1) "" else "s"}"
    }

    private fun confirmRemove(item: PlaylistStore.Item) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove playlist")
            .setMessage("Remove \"${item.name}\"? It disappears from the app after Save & Reset.")
            .setPositiveButton("Remove") { _, _ ->
                saved.remove(item)
                item.localPath?.let { removedLocalPaths.add(it) }
                rebuildRows()
                markDirty()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===================== Add =====================

    private fun addPlaylist() {
        val name = nameField.text.toString().trim()
        val url = urlField.text.toString().trim()
        val picked = pickedFile

        if (name.isEmpty()) {
            alert("Name required", "Give this playlist an extension name — it becomes the provider name.")
            return
        }

        val item = when {
            picked != null -> {
                val path = PlaylistStore.importFile(requireContext().applicationContext, picked.second)
                if (path == null) {
                    alert("Could not save file", "Importing the picked file failed. Try again.")
                    return
                }
                PlaylistStore.Item(name = uniqueName(name), url = "", localPath = path)
            }
            url.isNotEmpty() -> {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    alert("Invalid URL", "The playlist URL must start with http:// or https://.")
                    return
                }
                PlaylistStore.Item(name = uniqueName(name), url = url, localPath = null)
            }
            else -> {
                alert("Nothing to add", "Enter a playlist URL or pick an M3U / M3U8 file from the device.")
                return
            }
        }

        saved.add(item)
        pickedFile = null
        nameField.setText("")
        urlField.setText("")
        refreshPickedRow()
        rebuildRows()
        markDirty()
    }

    // ===================== Bottom bar =====================

    private fun bottomBar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(14))
            background = ColorDrawable(StreamVerseTheme.BG)
        }

        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        val cancel = text("Cancel", 14f, StreamVerseTheme.SUBTLE, true).apply {
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true
            isFocusable = true
            background = ctx.ghost(12)
            setOnClickListener { dismiss() }
        }
        bar.addView(cancel)

        saveButton = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ctx.accentBg()
            setPadding(dp(18), dp(11), dp(20), dp(11))
            contentDescription = "Save and reset"
            addView(ImageView(ctx).apply {
                setImageDrawable(SvIconDrawable(SvIconDrawable.Kind.RESET, Color.WHITE))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(text("Save & Reset", 14f, Color.WHITE, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { saveAndReset() }
        }

        bar.addView(saveButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })

        updateSaveButton()
        return bar
    }

    // ===================== Save / Reset =====================

    private fun markDirty() {
        dirty = true
        updateSaveButton()
    }

    private fun updateSaveButton() {
        if (::saveButton.isInitialized) saveButton.alpha = if (dirty) 1f else 0.55f
    }

    private fun saveAndReset() {
        val appCtx = requireContext().applicationContext

        // Local files of removed playlists are deleted immediately
        removedLocalPaths.forEach { path ->
            PlaylistStore.deleteLocal(path)
            PlaylistFetcher.clear(PlaylistSource.Local(path))
        }
        removedLocalPaths.clear()

        PlaylistStore.save(appCtx, saved.toList())
        dirty = false
        updateSaveButton()

        AlertDialog.Builder(requireContext())
            .setTitle("Playlists saved")
            .setMessage("Restart the app now? Every saved playlist is registered as its own separate provider on the next start.")
            .setPositiveButton("Restart") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent.makeRestartActivityTask(componentName))
            } else {
                context.startActivity(
                    Intent().setComponent(componentName).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                )
            }
            Runtime.getRuntime().exit(0)
        }
    }

    // ===================== Shared widgets =====================

    private fun alert(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun iconButton(kind: SvIconDrawable.Kind, color: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(requireContext()).apply {
            setImageDrawable(SvIconDrawable(kind, color))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true
            isFocusable = true
            background = requireContext().buttonBg()
            contentDescription = description
            setOnClickListener { onClick() }
        }
}
