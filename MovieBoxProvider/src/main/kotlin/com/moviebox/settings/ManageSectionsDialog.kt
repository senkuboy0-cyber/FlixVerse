package com.moviebox.settings

import com.moviebox.MovieBoxSections

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * "Manage Sections": tick to show on the home page, ▲▼ to reorder, ✎ to rename (not for default sections).
 * Changes are kept in memory until Save; Reset goes back to the defaults (applied on Save).
 */
class ManageSectionsDialog(
    context: Context,
    private val res: Resources,
    private val onSaved: () -> Unit,
) : Dialog(context) {

    private val accent = Color.parseColor("#4F8BFF")
    private val state: MutableList<MovieBoxSections.State> = MovieBoxSections.load()
    private lateinit var listBox: LinearLayout
    private val rows = mutableListOf<View>()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun drawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", "com.moviebox")
        return if (id != 0) res.getDrawable(id, null) else null
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
        titles.addView(text("MOVIEBOX", 12f, Color.parseColor("#9A9A9A")).apply { letterSpacing = 0.1f })
        titles.addView(text("Manage Sections", 22f, Color.WHITE, true))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Reset", accent) { confirmReset() })
        root.addView(header)

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
            MovieBoxSections.save(ordered)
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
        refreshArrows()
    }

    private fun makeRow(st: MovieBoxSections.State): View {
        val sec = MovieBoxSections.find(st.key)
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
        val title = text(MovieBoxSections.titleOf(st), 15f, Color.WHITE)
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

    private fun refreshArrows() {
        rows.forEachIndexed { i, row ->
            row.findViewWithTag<TextView>("up")?.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.3f }
            row.findViewWithTag<TextView>("down")?.apply { isEnabled = i < rows.size - 1; alpha = if (i < rows.size - 1) 1f else 0.3f }
        }
    }

    private fun move(st: MovieBoxSections.State, dir: Int) {
        val i = state.indexOf(st)
        val j = i + dir
        if (i < 0 || j < 0 || j >= state.size) return
        state.removeAt(i); state.add(j, st)
        val view = rows.removeAt(i); rows.add(j, view)
        listBox.removeViewAt(i); listBox.addView(view, j)
        refreshArrows()
        (if (dir < 0) view.findViewWithTag<View>("up") else view.findViewWithTag<View>("down"))?.requestFocus()
    }

    private fun rename(st: MovieBoxSections.State, titleView: TextView) {
        val sec = MovieBoxSections.find(st.key) ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            hint = sec.title
            // Start with the current name so a small edit doesn't need full retyping
            setText(MovieBoxSections.titleOf(st))
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
                titleView.text = MovieBoxSections.titleOf(st)
            }
            .setNeutralButton("Default") { _, _ ->
                st.name = null
                titleView.text = MovieBoxSections.titleOf(st)
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
                state.addAll(MovieBoxSections.ALL.map { MovieBoxSections.State(it.key, it.isDefault) })
                buildRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
