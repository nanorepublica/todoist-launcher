package uk.co.softwarecrafts.contextlauncher.ui.settings

import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.olauncher.R
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showKeyboard
import java.time.LocalTime

/** An option in a choice dialog: a stable id and what the user sees. */
data class Option(val id: String, val label: CharSequence)

/** The handful of input dialogs the settings forms are built from. All text-only, all using the app's own dialog. */
object FormDialogs {

    fun text(ctx: Context, title: CharSequence, initial: String, hint: CharSequence? = null, number: Boolean = false, onDone: (String) -> Unit) {
        var input: EditText? = null
        val dialog = ctx.createDialog(title = title, action = ctx.getString(R.string.okay), onAction = {
            input?.hideKeyboard()
            onDone(input?.text?.toString()?.trim().orEmpty())
        }) { container ->
            EditText(container.context, null, 0, R.style.TextSmall).apply {
                setText(initial)
                setSelection(initial.length)
                this.hint = hint
                inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 2
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
                input = this
            }
        }
        dialog.showRespectingStatusBar()
        input?.requestFocus()
        input?.showKeyboard()
    }

    fun number(ctx: Context, title: CharSequence, initial: Int?, hint: CharSequence? = null, onDone: (Int?) -> Unit) =
        text(ctx, title, initial?.toString().orEmpty(), hint, number = true) { onDone(it.toIntOrNull()?.takeIf { n -> n > 0 }) }

    fun time(ctx: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
    }

    fun choice(ctx: Context, title: CharSequence, options: List<Option>, selected: String?, onPick: (String) -> Unit) {
        var dialog: OlDialog? = null
        dialog = ctx.createDialog(title = title, action = ctx.getString(R.string.cancel)) { container ->
            list(container, options.size) {
                options.forEach { opt ->
                    addView(TextView(context, null, 0, R.style.TextSmall).apply {
                        text = (if (opt.id == selected) "● " else "○ ") + opt.label
                        setPadding(24, 20, 24, 20)
                        setOnClickListener { dialog?.dismiss(); onPick(opt.id) }
                    })
                }
            }
        }
        dialog.showRespectingStatusBar()
    }

    fun multiChoice(ctx: Context, title: CharSequence, options: List<Option>, selected: Set<String>, max: Int? = null, onDone: (Set<String>) -> Unit) {
        val chosen = selected.toMutableSet()
        val dialog = ctx.createDialog(title = title, action = ctx.getString(R.string.okay), onAction = { onDone(chosen.toSet()) }) { container ->
            list(container, options.size) {
                options.forEach { opt ->
                    addView(TextView(context, null, 0, R.style.TextSmall).apply {
                        fun refresh() { text = (if (opt.id in chosen) "☑ " else "☐ ") + opt.label }
                        refresh()
                        setPadding(24, 20, 24, 20)
                        setOnClickListener {
                            if (opt.id in chosen) chosen -= opt.id
                            else if (max == null || chosen.size < max) chosen += opt.id
                            refresh()
                        }
                    })
                }
            }
        }
        dialog.showRespectingStatusBar()
    }

    fun confirm(ctx: Context, title: CharSequence, message: CharSequence, action: CharSequence, onAction: () -> Unit) {
        ctx.createDialog(title = title, action = action, message = message, onAction = onAction).showRespectingStatusBar()
    }

    /** A column that scrolls inside the dialog once it is long, instead of pushing the dialog off screen. */
    private fun list(container: ViewGroup, count: Int, fill: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(container.context).apply { orientation = LinearLayout.VERTICAL; fill() }
        return ScrollView(container.context).apply {
            isVerticalScrollBarEnabled = true
            addView(column)
            val maxHeight = resources.displayMetrics.heightPixels / 2
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (count > 6) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
