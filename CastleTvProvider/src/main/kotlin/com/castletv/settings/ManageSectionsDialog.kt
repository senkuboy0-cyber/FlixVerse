package com.castletv.settings

import com.castletv.CastleSections

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * "Manage Sections": tick to show on the home page, ▲▼ to reorder, ✎ to rename (not for default sections).
 * Changes are kept in memory until Save; Reset goes back to the defaults (applied on Save).
 * Search filters the list by name; ▲▼ then move past the hidden rows to the next shown one.
 */
class ManageSectionsDialog(
    context: Context,
    private val onSaved: () -> Unit,
) : Dialog(context) {

    private val accent = Ui.ACCENT
    private val state: MutableList<CastleSections.State> = CastleSections.load()
    private lateinit var listBox: LinearLayout
    private val rows = mutableListOf<View>()
    private lateinit var searchBox: EditText
    private var query = ""

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun drawable(name: String): Drawable? = when (name) {
        "outline" -> Ui.outline(context)
        "dialog_background" -> Ui.dialogBackground(context)
        "section_row_background" -> Ui.sectionRow(context)
        else -> null
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(s: String, color: Int, onClick: () -> Unit) = text(s, 16f, color).apply {
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        isFocusable = true
        background = drawable("outline")
        setOnClickListener { onClick() }
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(Color.parseColor("#2A2A2A"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = drawable("dialog_background") ?: GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("CASTLE TV", 12f, Color.parseColor("#9A9A9A")).apply { letterSpacing = 0.1f })
        titles.addView(text("Manage Sections", 22f, Color.WHITE, true))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        actions.addView(
            button("Reset", accent) { confirmReset() }.apply { maxLines = 1 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        // Search icon (magnifier) under Reset
        val searchBtn: View = ImageView(context).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.SEARCH, accent))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Search"
        }
        searchBtn.apply {
            isClickable = true
            isFocusable = true
            background = drawable("outline")
            setOnClickListener { toggleSearch() }
        }
        actions.addView(searchBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(6); gravity = Gravity.END })
        header.addView(actions)
        root.addView(header)

        // Search field (hidden until Search is pressed)
        searchBox = EditText(context).apply {
            visibility = View.GONE
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Search sections"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222")); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); applyFilter() }
            })
        }
        root.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        root.addView(divider().apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(12), 0, dp(8))
        })
        root.addView(text("Tick to show  ·  ▲▼ to reorder  ·  ✎ to rename", 12f, Color.parseColor("#8A8A8A")).apply {
            setPadding(dp(4), 0, 0, dp(8))
        })

        // List
        listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(divider().apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(8), 0, dp(4))
        })

        // Footer
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        footer.addView(button("Cancel", Color.parseColor("#AAAAAA")) { dismiss() })
        footer.addView(button("Save", accent) {
            // Ticked sections first (their order kept), unticked after: a newly ticked one lands right after the last ticked
            val ordered = state.filter { it.enabled } + state.filter { !it.enabled }
            CastleSections.save(ordered)
            dismiss()
            onSaved()
        })
        root.addView(footer)

        setContentView(root)
        buildRows()
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            val dm = context.resources.displayMetrics
            val width = minOf((dm.widthPixels * 0.92f).toInt(), dp(520))
            setLayout(width, (dm.heightPixels * 0.88f).toInt())
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

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

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) {
            searchBox.setText("")
            searchBox.visibility = View.GONE
        } else {
            searchBox.visibility = View.VISIBLE
            searchBox.requestFocus()
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun matches(st: CastleSections.State): Boolean {
        if (query.isEmpty()) return true
        val sec = CastleSections.find(st.key)
        return CastleSections.titleOf(st).contains(query, true) || sec?.label?.contains(query, true) == true
            || sec?.title?.contains(query, true) == true
    }

    /** Show only matching rows (all when the search is empty). */
    private fun applyFilter() {
        state.forEachIndexed { i, st -> rows.getOrNull(i)?.visibility = if (matches(st)) View.VISIBLE else View.GONE }
        refreshArrows()
    }

    private fun makeRow(st: CastleSections.State): View {
        val sec = CastleSections.find(st.key)
        val isDefault = sec?.isDefault == true

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = drawable("section_row_background") ?: GradientDrawable().apply {
                setColor(Color.parseColor("#222222")); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(8), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(8)) }
        }

        val check = CheckBox(context).apply {
            isChecked = st.enabled
            buttonTintList = ColorStateList.valueOf(accent)
        }
        row.addView(check)

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(4), 0)
        }
        val title = text(CastleSections.titleOf(st), 15f, Color.WHITE)
        val sub = text((if (isDefault) "Default · " else "") + (sec?.label ?: st.key), 11f, Color.parseColor("#8A8A8A"))
        texts.addView(title)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun applyAlpha() { texts.alpha = if (st.enabled) 1f else 0.5f }
        applyAlpha()
        check.setOnCheckedChangeListener { _, on -> st.enabled = on; applyAlpha() }
        texts.isClickable = true
        texts.setOnClickListener { check.isChecked = !check.isChecked }

        if (!isDefault) {
            row.addView(arrow("✎") { rename(st, title) })
        } else {
            row.addView(View(context), LinearLayout.LayoutParams(dp(40), 1))
        }
        val up = arrow("▲") { move(st, -1) }.apply { tag = "up" }
        val down = arrow("▼") { move(st, 1) }.apply { tag = "down" }
        row.addView(up)
        row.addView(down)
        return row
    }

    private fun arrow(s: String, onClick: () -> Unit) = text(s, 17f, accent).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        isClickable = true
        isFocusable = true
        background = drawable("outline")
        setOnClickListener { onClick() }
    }

    /** Index of the next shown row from i in direction dir, or -1. */
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
            row.findViewWithTag<TextView>("up")?.apply { isEnabled = canUp; alpha = if (canUp) 1f else 0.3f }
            row.findViewWithTag<TextView>("down")?.apply { isEnabled = canDown; alpha = if (canDown) 1f else 0.3f }
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
    }

    private fun rename(st: CastleSections.State, titleView: TextView) {
        val sec = CastleSections.find(st.key) ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            hint = sec.title
            // Start with the current name so a small edit doesn't need full retyping
            setText(CastleSections.titleOf(st))
            setSelection(text.length)
        }
        val box = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle("Home page name")
            .setMessage("Empty = default name (${sec.title})")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                st.name = input.text.toString().trim().takeIf { it.isNotEmpty() && it != sec.title }
                titleView.text = CastleSections.titleOf(st)
            }
            .setNeutralButton("Default") { _, _ ->
                st.name = null
                titleView.text = CastleSections.titleOf(st)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(context)
            .setTitle("Reset sections?")
            .setMessage("Order, ticks and custom names go back to default. Press Save to apply.")
            .setPositiveButton("Reset") { _, _ ->
                state.clear()
                state.addAll(CastleSections.ALL.map { CastleSections.State(it.key, it.isDefault) })
                buildRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
