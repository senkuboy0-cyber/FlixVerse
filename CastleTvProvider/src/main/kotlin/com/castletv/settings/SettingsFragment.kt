package com.castletv.settings

import com.castletv.CastleSections

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * reordered, renamed and filtered by tab or search. Changes live in memory until Save.
 *
 * Every icon is drawn with Canvas (IconDrawable / CheckCircleDrawable); all focusable
 * views have a visible focus ring so the screen works with a TV remote.
 */
class SettingsFragment(
    private val sharedPref: SharedPreferences,
) : DialogFragment() {

    // Dark palette shared by the whole screen.
    private object C {
        val BG = Color.parseColor("#0E0F13")
        val SURFACE = Color.parseColor("#171A20")
        val SURFACE_HI = Color.parseColor("#242833")
        val STROKE = Color.parseColor("#2A2F38")
        val TEXT = Color.parseColor("#F4F6F9")
        val SUBTLE = Color.parseColor("#9AA1AD")
        val FAINT = Color.parseColor("#6B7280")
        val ACCENT = Color.parseColor("#4F8BFF")
        val ACCENT_HI = Color.parseColor("#6FA0FF")
        val DANGER = Color.parseColor("#E57373")
    }

    private val state: MutableList<CastleSections.State> = CastleSections.load()
    private val rows = mutableListOf<View>()
    private var query = ""
    private var tabFilter: String? = null // null = ALL

    private lateinit var listBox: LinearLayout
    private lateinit var emptyBox: View
    private lateinit var counter: TextView
    private lateinit var saveButton: TextView
    private lateinit var chipRow: LinearLayout
    private val chips = mutableListOf<Pair<TextView, String?>>() // view to tab key (null = ALL)
    private var dirty = false

    private fun dp(v: Int) = requireContext().dp(v)

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(requireContext()).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    /** Full-screen window, dimmed nothing: the screen paints its own background. */
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
        column.addView(searchCard(ctx))
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
            background = ColorDrawable(C.BG)
            addView(column, FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }

        buildRows()
        return root
    }

    // ===================== UI parts =====================

    /** Back arrow, title block and the shown/total counter. */
    private fun toolbar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(20), dp(10))
        }
        bar.addView(iconButton(ctx, IconDrawable.Kind.BACK, C.SUBTLE, "Back") { dismiss() })

        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        titles.addView(text("Manage Sections", 19f, C.TEXT, true))
        titles.addView(text("CASTLE TV · HOME PAGE", 10f, C.FAINT).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(2), 0, 0)
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        counter = text("", 12f, C.FAINT, true)
        bar.addView(counter)
        return bar
    }

    /** Search field, always visible; the cross clears the query. */
    private fun searchCard(ctx: Context): View {
        val clear = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.CLEAR, C.SUBTLE))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            background = ghost(ctx, 12)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "Clear search"
            visibility = View.GONE
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(ctx, C.SURFACE, 14, 1, C.STROKE)
            setPadding(dp(14), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(16), dp(6), dp(16), dp(2)) }
        }
        card.addView(ImageView(ctx).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.SEARCH, C.FAINT))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(20), dp(20)))

        val field = EditText(ctx).apply {
            setBackgroundDrawable(null)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Search sections"
            setHintTextColor(C.FAINT)
            setTextColor(C.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(12), dp(6), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    applyFilter()
                }
            })
        }
        card.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        clear.setOnClickListener {
            field.setText("")
            field.requestFocus()
        }
        card.addView(clear, LinearLayout.LayoutParams(dp(36), dp(36)))
        return card
    }

    /** Tab filter chips: ALL / HOME / SERIES / MOVIES / ANIME, in catalog order. */
    private fun chipsBar(ctx: Context): View {
        chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        chips.clear()
        fun chip(label: String, tab: String?) {
            val c = text(label, 12f, C.SUBTLE, true).apply {
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
                shape(requireContext(), C.ACCENT, 20, 2, Color.WHITE)
            } else {
                states(
                    shape(requireContext(), C.SURFACE_HI, 20, 2, Color.WHITE),
                    shape(requireContext(), C.SURFACE_HI, 20),
                    shape(requireContext(), C.SURFACE, 20, 1, C.STROKE),
                )
            }
            chip.setTextColor(if (selected) Color.WHITE else C.SUBTLE)
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
            setImageDrawable(IconDrawable(IconDrawable.Kind.SEARCH, C.STROKE))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(text("No sections found", 15f, C.SUBTLE, true).apply { setPadding(0, dp(14), 0, 0) })
        box.addView(text("Try another keyword or tab", 12f, C.FAINT).apply { setPadding(0, dp(3), 0, 0) })
        return box
    }

    /** Reset on the left, Cancel and Save on the right. */
    private fun bottomBar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(14))
            background = ColorDrawable(C.BG)
        }

        val reset = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ghost(ctx, 12)
            setPadding(dp(14), dp(10), dp(16), dp(10))
            contentDescription = "Reset to defaults"
            addView(ImageView(ctx).apply {
                setImageDrawable(IconDrawable(IconDrawable.Kind.RESET, C.DANGER))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(text("Reset", 14f, C.DANGER, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { confirmReset() }
        }
        bar.addView(reset)

        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        val cancel = text("Cancel", 14f, C.SUBTLE, true).apply {
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true
            isFocusable = true
            background = ghost(ctx, 12)
            setOnClickListener { dismiss() }
        }
        bar.addView(cancel)

        saveButton = text("Save", 14f, Color.WHITE, true).apply {
            setPadding(dp(24), dp(11), dp(24), dp(11))
            isClickable = true
            isFocusable = true
            background = states(
                shape(ctx, C.ACCENT, 12, 2, Color.WHITE),
                shape(ctx, C.ACCENT_HI, 12),
                shape(ctx, C.ACCENT, 12),
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
                shape(ctx, C.SURFACE_HI, 16, 2, Color.WHITE),
                shape(ctx, C.SURFACE_HI, 16),
                shape(ctx, C.SURFACE, 16, 1, C.STROKE),
            )
            setPadding(dp(12), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), 0, dp(4), dp(8)) }
            isClickable = true
            isFocusable = true
        }

        // Toggle circle: filled accent with a check when shown, dark ring when hidden.
        val toggle = CheckCircleDrawable(C.ACCENT, C.SURFACE_HI, C.FAINT, Color.WHITE)
        toggle.checked = st.enabled
        val toggleView = ImageView(ctx).apply {
            setImageDrawable(toggle)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(toggleView, LinearLayout.LayoutParams(dp(30), dp(30)))

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        val title = text(CastleSections.titleOf(st), 15f, C.TEXT, true)
        val sub = text((if (isDefault) "Default · " else "") + (sec?.label ?: st.key), 11f, C.FAINT).apply {
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

        if (!isDefault) {
            row.addView(iconButton(ctx, IconDrawable.Kind.PENCIL, C.SUBTLE, "Rename") { rename(st, title) })
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(dp(38), 1))
        }
        row.addView(iconButton(ctx, IconDrawable.Kind.CHEVRON_UP, C.SUBTLE, "Move up") { move(st, -1) }.apply { tag = "up" })
        row.addView(iconButton(ctx, IconDrawable.Kind.CHEVRON_DOWN, C.SUBTLE, "Move down") { move(st, 1) }.apply { tag = "down" })
        return row
    }

    private fun iconButton(ctx: Context, kind: IconDrawable.Kind, color: Int, description: String, onClick: () -> Unit): View =
        ImageView(ctx).apply {
            setImageDrawable(IconDrawable(kind, color))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            background = ghost(ctx, 12)
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

// ===================== Drawing helpers =====================

private fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

private fun shape(ctx: Context, fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = ctx.dp(radius).toFloat()
        if (stroke > 0) setStroke(ctx.dp(stroke), strokeColor)
    }

private fun states(focused: Drawable, pressed: Drawable?, normal: Drawable) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), focused)
    pressed?.let { addState(intArrayOf(android.R.attr.state_pressed), it) }
    addState(intArrayOf(), normal)
}

/** Transparent button that shows a white ring on focus (TV remote friendly). */
private fun ghost(ctx: Context, radius: Int = 12): Drawable = states(
    shape(ctx, Color.parseColor("#33FFFFFF"), radius, 2, Color.WHITE),
    shape(ctx, Color.parseColor("#33FFFFFF"), radius),
    shape(ctx, Color.TRANSPARENT, radius),
)

/**
 * Line icons drawn with Canvas on a 24×24 grid: no XML vectors, no emoji, no font glyphs.
 * Stroke style: 2px round caps, similar to Feather/Lucide line icons.
 */
class IconDrawable(private val kind: Kind, color: Int = Color.WHITE) : Drawable() {
    enum class Kind { BACK, SEARCH, CLEAR, PENCIL, CHEVRON_UP, CHEVRON_DOWN, RESET, CHECK }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) =
        canvas.drawLine(x1, y1, x2, y2, paint)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        when (kind) {
            Kind.BACK -> {
                line(canvas, 20f, 12f, 5f, 12f)
                line(canvas, 11f, 6f, 5f, 12f)
                line(canvas, 11f, 18f, 5f, 12f)
            }
            Kind.SEARCH -> {
                canvas.drawCircle(11f, 11f, 6.5f, paint)
                line(canvas, 15.8f, 15.8f, 20.5f, 20.5f)
            }
            Kind.CLEAR -> {
                line(canvas, 6.5f, 6.5f, 17.5f, 17.5f)
                line(canvas, 17.5f, 6.5f, 6.5f, 17.5f)
            }
            Kind.PENCIL -> {
                // Body + rounded tip (Feather "edit-2")
                val pencil = Path().apply {
                    moveTo(17f, 3f)
                    arcTo(RectF(16.17f, 2.17f, 21.83f, 7.83f), 225f, 180f)
                    lineTo(7.5f, 20.5f)
                    lineTo(2f, 22f)
                    lineTo(3.5f, 16.5f)
                    close()
                }
                canvas.drawPath(pencil, paint)
            }
            Kind.CHEVRON_UP -> {
                line(canvas, 6f, 14.5f, 12f, 8.5f)
                line(canvas, 12f, 8.5f, 18f, 14.5f)
            }
            Kind.CHEVRON_DOWN -> {
                line(canvas, 6f, 9.5f, 12f, 15.5f)
                line(canvas, 12f, 15.5f, 18f, 9.5f)
            }
            Kind.RESET -> {
                // Open circle with an arrow head at the gap (refresh / undo)
                canvas.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), 60f, 280f, false, paint)
                val endAngle = Math.toRadians(340.0)
                val ex = 12f + 7.5f * Math.cos(endAngle).toFloat()
                val ey = 12f + 7.5f * Math.sin(endAngle).toFloat()
                // Two wings pointing back along the arc direction (tangent at 340° is 70°)
                listOf(215.0, 285.0).forEach { deg ->
                    val a = Math.toRadians(deg)
                    line(canvas, ex, ey, ex + 4f * Math.cos(a).toFloat(), ey + 4f * Math.sin(a).toFloat())
                }
            }
            Kind.CHECK -> {
                line(canvas, 5f, 12.5f, 10f, 17.5f)
                line(canvas, 10f, 17.5f, 19f, 7f)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Round toggle: accent disc with a check when on, dark disc with a ring when off. */
class CheckCircleDrawable(
    private val onColor: Int,
    private val offFill: Int,
    private val offRing: Int,
    private val checkColor: Int,
) : Drawable() {

    var checked: Boolean = false
        set(value) { field = value; invalidateSelf() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = checkColor
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val cx = b.left + b.width() / 2f
        val cy = b.top + b.height() / 2f
        val r = size * 0.42f
        if (checked) {
            fill.color = onColor
            canvas.drawCircle(cx, cy, r, fill)
            val s = size / 24f
            canvas.save()
            canvas.translate(cx - 12f * s, cy - 12f * s)
            canvas.scale(s, s)
            canvas.drawLine(5f, 12.5f, 10f, 17.5f, check)
            canvas.drawLine(10f, 17.5f, 19f, 7f, check)
            canvas.restore()
        } else {
            fill.color = offFill
            ring.color = offRing
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r - 1f, ring)
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha; ring.alpha = alpha; check.alpha = alpha; invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter; ring.colorFilter = colorFilter; check.colorFilter = colorFilter; invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
