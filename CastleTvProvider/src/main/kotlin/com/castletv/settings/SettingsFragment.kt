package com.castletv.settings

import com.castletv.CastleSections

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Castle TV settings, one full-screen screen built entirely in code (no XML, no emoji).
 * Opens directly on "Manage Sections": the home page categories can be shown or hidden,
 * reordered (arrow buttons), renamed (pencil) and filtered by tab or by the search box,
 * which only opens when the toolbar search button is pressed.
 * Changes live in memory until Save.
 *
 * Every icon is drawn with Canvas (SettingsIcons.kt); the palette and view helpers live
 * in SettingsUi.kt. All focusable views have a visible focus ring so the screen works
 * with a TV remote.
 */
class SettingsFragment(
    private val sharedPref: SharedPreferences,
) : DialogFragment() {

    private val state: MutableList<CastleSections.State> = CastleSections.load()
    private val rows = mutableListOf<View>()
    private var query = ""
    private var tabFilter: String? = null // null = ALL

    private lateinit var listBox: LinearLayout
    private lateinit var emptyBox: View
    private lateinit var counter: TextView
    private lateinit var saveButton: TextView
    private lateinit var searchToggle: ImageView
    private lateinit var searchCardView: View
    private lateinit var searchField: EditText
    private lateinit var chipRow: LinearLayout
    private val chips = mutableListOf<Pair<TextView, String?>>() // view to tab key (null = ALL)
    private var dirty = false

    private fun dp(v: Int) = requireContext().dp(v)
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        requireContext().text(s, size, color, bold)

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
        // Full-bleed background, content capped at 640dp so rows stay readable on TV/tablets.
        val contentWidth = minOf(ctx.dp(640), (dm.widthPixels * 0.96f).toInt())

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        column.addView(toolbar(ctx))
        column.addView(searchCard(ctx)) // starts GONE, opened from the toolbar search button
        column.addView(chipsBar(ctx))

        val listScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(listBox)
            setPadding(0, 0, 0, 0)
        }
        emptyBox = emptyState(ctx)
        val listArea = FrameLayout(ctx).apply {
            addView(listScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        column.addView(listArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        column.addView(bottomBar(ctx))

        val root = FrameLayout(ctx).apply {
            background = ColorDrawable(SettingsTheme.BG)
            addView(column, FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }

        buildRows()
        return root
    }

    // ===================== Toolbar =====================

    /** Back button, title block, the search toggle and the shown/total counter. */
    private fun toolbar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(10))
        }
        bar.addView(
            iconButton(IconDrawable.Kind.BACK, SettingsTheme.SUBTLE, "Back") { dismiss() },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )

        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(text("Manage Sections", 19f, SettingsTheme.TEXT, true))
        titles.addView(text("CASTLE TV · HOME PAGE", 10f, SettingsTheme.FAINT).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(2), 0, 0)
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        searchToggle = iconButton(IconDrawable.Kind.SEARCH, SettingsTheme.SUBTLE, "Search") { toggleSearch() }
        bar.addView(searchToggle, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })

        counter = text("", 12f, SettingsTheme.FAINT, true)
        bar.addView(counter)
        return bar
    }

    // ===================== Search (toggleable) =====================

    /**
     * Hidden by default. Opens from the toolbar search button; while it is open the
     * magnifier stays highlighted. The cross inside clears the text, and once the text
     * is already empty it closes the whole search bar again.
     */
    private fun searchCard(ctx: Context): View {
        val clear = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.CLEAR, SettingsTheme.SUBTLE))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            background = ctx.ghost(12)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "Clear or close search"
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.shape(SettingsTheme.SURFACE, 14, 1, SettingsTheme.STROKE)
            setPadding(dp(14), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(16), dp(6), dp(16), dp(2)) }
            visibility = View.GONE
        }
        card.addView(ImageView(ctx).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.SEARCH, SettingsTheme.FAINT))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(20), dp(20)))

        val field = EditText(ctx).apply {
            setBackgroundDrawable(null)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Search sections"
            setHintTextColor(SettingsTheme.FAINT)
            setTextColor(SettingsTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(12), dp(6), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    applyFilter()
                }
            })
        }
        searchField = field
        card.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        clear.setOnClickListener {
            if (field.text.isNotEmpty()) {
                field.setText("")
                field.requestFocus()
            } else {
                closeSearch()
            }
        }
        card.addView(clear, LinearLayout.LayoutParams(dp(36), dp(36)))

        searchCardView = card
        return card
    }

    /** Toolbar search button: show the field + keyboard, or hide it and reset the filter. */
    private fun toggleSearch() {
        if (searchCardView.visibility == View.VISIBLE) closeSearch() else openSearch()
    }

    private fun openSearch() {
        searchCardView.visibility = View.VISIBLE
        styleSearchToggle(true)
        searchField.post {
            searchField.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchCardView.visibility = View.GONE
        styleSearchToggle(false)
        if (searchField.text.isNotEmpty()) searchField.setText("") // resets the filter via the watcher
        searchToggle.requestFocus()
    }

    private fun styleSearchToggle(active: Boolean) {
        val ctx = requireContext()
        searchToggle.setImageDrawable(
            IconDrawable(IconDrawable.Kind.SEARCH, if (active) SettingsTheme.ACCENT_HI else SettingsTheme.SUBTLE)
        )
        searchToggle.background = if (active) {
            ctx.shape(SettingsTheme.ACCENT_DIM, 12, 1, SettingsTheme.ACCENT)
        } else {
            ctx.buttonBg()
        }
    }

    // ===================== Tab filter chips =====================

    /** Tab filter chips: ALL / HOME / SERIES / MOVIES / ANIME, in catalog order. */
    private fun chipsBar(ctx: Context): View {
        chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        chips.clear()
        fun chip(label: String, tab: String?) {
            val c = text(label, 12f, SettingsTheme.SUBTLE, true).apply {
                tag = tab
                letterSpacing = 0.06f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    tabFilter = tab
                    styleChips()
                    applyFilter()
                }
            }
            chips.add(c to tab)
            chipRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
        chip("ALL", null)
        CastleSections.ALL.map { it.key.substringBefore("|") }.distinct().forEach { prefix ->
            tabName(prefix)?.let { chip(it.uppercase(), prefix) }
        }
        styleChips()

        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        return scroll
    }

    private fun tabName(prefix: String): String? = when (prefix) {
        "1001" -> "Home"
        "1002" -> "Series"
        "1003" -> "Movies"
        "1069" -> "Anime"
        else -> null
    }

    private fun styleChips() {
        chips.forEach { (chip, tab) ->
            val selected = tab == tabFilter
            chip.background = if (selected) {
                requireContext().shape(SettingsTheme.ACCENT, 20, 2, Color.WHITE)
            } else {
                states(
                    requireContext().shape(SettingsTheme.SURFACE_HI, 20, 2, Color.WHITE),
                    requireContext().shape(SettingsTheme.SURFACE_HI, 20),
                    requireContext().shape(SettingsTheme.SURFACE, 20, 1, SettingsTheme.STROKE),
                )
            }
            chip.setTextColor(if (selected) Color.WHITE else SettingsTheme.SUBTLE)
        }
    }

    /** Shown while the filters hide every row. */
    private fun emptyState(ctx: Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = false
        }
        box.addView(ImageView(ctx).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.SEARCH, SettingsTheme.STROKE))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(text("No sections found", 15f, SettingsTheme.SUBTLE, true).apply { setPadding(0, dp(14), 0, 0) })
        box.addView(text("Try another keyword or tab", 12f, SettingsTheme.FAINT).apply { setPadding(0, dp(3), 0, 0) })
        return box
    }

    // ===================== Bottom bar =====================

    /** Reset on the left, Cancel and Save on the right. */
    private fun bottomBar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(14))
            background = ColorDrawable(SettingsTheme.BG)
        }

        val reset = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ctx.ghost(12)
            setPadding(dp(14), dp(10), dp(16), dp(10))
            contentDescription = "Reset to defaults"
            addView(ImageView(ctx).apply {
                setImageDrawable(IconDrawable(IconDrawable.Kind.RESET, SettingsTheme.DANGER))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(text("Reset", 14f, SettingsTheme.DANGER, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { confirmReset() }
        }
        bar.addView(reset)

        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        val cancel = text("Cancel", 14f, SettingsTheme.SUBTLE, true).apply {
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true
            isFocusable = true
            background = ctx.ghost(12)
            setOnClickListener { dismiss() }
        }
        bar.addView(cancel)

        saveButton = text("Save", 14f, Color.WHITE, true).apply {
            setPadding(dp(24), dp(11), dp(24), dp(11))
            isClickable = true
            isFocusable = true
            background = states(
                ctx.shape(SettingsTheme.ACCENT, 12, 2, Color.WHITE),
                ctx.shape(SettingsTheme.ACCENT_HI, 12),
                ctx.shape(SettingsTheme.ACCENT, 12),
            )
            alpha = if (dirty) 1f else 0.55f
            setOnClickListener { save() }
        }
        bar.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(10) })
        return bar
    }

    // ===================== Rows =====================

    private fun buildRows() {
        listBox.removeAllViews()
        rows.clear()
        state.forEach { st ->
            val row = makeRow(st)
            rows.add(row)
            listBox.addView(row)
        }
        applyFilter()
    }

    private fun makeRow(st: CastleSections.State): View {
        val ctx = requireContext()
        val sec = CastleSections.find(st.key)
        val isDefault = sec?.isDefault == true

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = states(
                ctx.shape(SettingsTheme.SURFACE_HI, 16, 2, Color.WHITE),
                ctx.shape(SettingsTheme.SURFACE_HI, 16),
                ctx.shape(SettingsTheme.SURFACE, 16, 1, SettingsTheme.STROKE),
            )
            setPadding(dp(12), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), 0, dp(4), dp(8)) }
            isClickable = true
            isFocusable = true
        }

        // Toggle circle: filled accent with a check when shown, dark ring when hidden.
        val toggle = CheckCircleDrawable(SettingsTheme.ACCENT, SettingsTheme.SURFACE_HI, SettingsTheme.FAINT, Color.WHITE)
        toggle.checked = st.enabled
        val toggleView = ImageView(ctx).apply {
            setImageDrawable(toggle)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(toggleView, LinearLayout.LayoutParams(dp(30), dp(30)))

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        val title = text(CastleSections.titleOf(st), 15f, SettingsTheme.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = text((if (isDefault) "Default · " else "") + (sec?.label ?: st.key), 11f, SettingsTheme.FAINT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(title)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun applyAlpha() {
            texts.alpha = if (st.enabled) 1f else 0.45f
            toggle.checked = st.enabled
            toggleView.contentDescription = if (st.enabled) "Shown" else "Hidden"
        }
        applyAlpha()

        row.setOnClickListener {
            st.enabled = !st.enabled
            applyAlpha()
            markDirty()
        }

        // Rename + reorder buttons: explicit sizes and a visible disc background, so
        // they can always be seen (previously they collapsed to zero and vanished).
        if (!isDefault) {
            row.addView(
                iconButton(IconDrawable.Kind.PENCIL, SettingsTheme.SUBTLE, "Rename") { rename(st, title) },
                rowIconParams(),
            )
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(dp(40), 1))
        }
        row.addView(
            iconButton(IconDrawable.Kind.CHEVRON_UP, SettingsTheme.SUBTLE, "Move up") { move(st, -1) }.apply { tag = "up" },
            rowIconParams(),
        )
        row.addView(
            iconButton(IconDrawable.Kind.CHEVRON_DOWN, SettingsTheme.SUBTLE, "Move down") { move(st, 1) }.apply { tag = "down" },
            rowIconParams(),
        )
        return row
    }

    private fun rowIconParams() = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }

    /** Small round button with a code-drawn icon; visible disc at rest, ring on focus. */
    private fun iconButton(kind: IconDrawable.Kind, color: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(requireContext()).apply {
            setImageDrawable(IconDrawable(kind, color))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true
            isFocusable = true
            background = requireContext().buttonBg()
            contentDescription = description
            setOnClickListener { onClick() }
        }

    // ===================== Filtering =====================

    private fun matches(st: CastleSections.State): Boolean {
        if (tabFilter != null && st.key.substringBefore("|") != tabFilter) return false
        if (query.isEmpty()) return true
        val sec = CastleSections.find(st.key)
        return CastleSections.titleOf(st).contains(query, true) || sec?.label?.contains(query, true) == true
            || sec?.title?.contains(query, true) == true
    }

    /** Show only matching rows, update the counter and the empty state. */
    private fun applyFilter() {
        var shown = 0
        state.forEachIndexed { i, st ->
            val visible = matches(st)
            rows.getOrNull(i)?.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) shown++
        }
        counter.text = "${shown} of ${state.size}"
        emptyBox.visibility = if (shown == 0) View.VISIBLE else View.GONE
        refreshArrows()
    }

    // ===================== Reordering =====================

    /** Index of the next shown row from i in direction dir, or -1 (hidden rows are skipped). */
    private fun nextShown(i: Int, dir: Int): Int {
        var j = i + dir
        while (j in rows.indices) {
            if (rows[j].visibility == View.VISIBLE) return j
            j += dir
        }
        return -1
    }

    private fun refreshArrows() {
        rows.forEachIndexed { i, row ->
            val canUp = nextShown(i, -1) >= 0
            val canDown = nextShown(i, 1) >= 0
            row.findViewWithTag<View>("up")?.apply { isEnabled = canUp; alpha = if (canUp) 1f else 0.25f }
            row.findViewWithTag<View>("down")?.apply { isEnabled = canDown; alpha = if (canDown) 1f else 0.25f }
        }
    }

    private fun move(st: CastleSections.State, dir: Int) {
        val i = state.indexOf(st)
        if (i < 0) return
        val j = nextShown(i, dir)
        if (j < 0) return
        state.removeAt(i); state.add(j, st)
        val view = rows.removeAt(i); rows.add(j, view)
        listBox.removeViewAt(i); listBox.addView(view, j)
        refreshArrows()
        (if (dir < 0) view.findViewWithTag<View>("up") else view.findViewWithTag<View>("down"))?.requestFocus()
        markDirty()
    }

    // ===================== Rename =====================

    private fun rename(st: CastleSections.State, titleView: TextView) {
        val ctx = requireContext()
        val sec = CastleSections.find(st.key) ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            hint = sec.title
            // Start with the current name so a small edit doesn't need full retyping
            setText(CastleSections.titleOf(st))
            setSelection(text.length)
        }
        val box = FrameLayout(ctx).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Home page name")
            .setMessage("Empty = default name (${sec.title})")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                st.name = input.text.toString().trim().takeIf { it.isNotEmpty() && it != sec.title }
                titleView.text = CastleSections.titleOf(st)
                markDirty()
            }
            .setNeutralButton("Default") { _, _ ->
                st.name = null
                titleView.text = CastleSections.titleOf(st)
                markDirty()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===================== Save / Reset =====================

    private fun markDirty() {
        dirty = true
        if (::saveButton.isInitialized) saveButton.alpha = 1f
    }

    private fun save() {
        // Ticked sections first (their order kept), unticked after: a newly ticked one lands right after the last ticked
        val ordered = state.filter { it.enabled } + state.filter { !it.enabled }
        CastleSections.save(ordered)
        dirty = false
        AlertDialog.Builder(requireContext())
            .setTitle("Changes saved")
            .setMessage("Restart the app now so the home page picks them up?")
            .setPositiveButton("Restart") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset sections?")
            .setMessage("Order, ticks and custom names go back to default. Press Save to apply.")
            .setPositiveButton("Reset") { _, _ ->
                state.clear()
                state.addAll(CastleSections.ALL.map { CastleSections.State(it.key, it.isDefault) })
                buildRows()
                markDirty()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            context.startActivity(Intent.makeRestartActivityTask(componentName))
            Runtime.getRuntime().exit(0)
        }
    }
}
