package com.ricardo.txtreader.ui.components

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView

private const val READ_FROM_SELECTION_ID = 9101

@Composable
fun SelectableReaderText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    highlightStart: Int? = null,
    highlightEnd: Int? = null,
    highlightColor: Int? = null,
    onTextViewReady: (TextView) -> Unit = {},
    onReadFromSelection: (String, Int) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                setLineSpacing(0f, 1.28f)
                customSelectionActionModeCallback = readFromSelectionCallback(this, onReadFromSelection)
            }
        },
        update = { view ->
            view.textSize = fontSize.value
            view.text = text.withOptionalHighlight(highlightStart, highlightEnd, highlightColor)
            view.customSelectionActionModeCallback = readFromSelectionCallback(view, onReadFromSelection)
            view.post { onTextViewReady(view) }
        }
    )
}

fun readFromSelectionCallback(
    view: TextView,
    onReadFromSelection: (String, Int) -> Unit
): ActionMode.Callback = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(0, READ_FROM_SELECTION_ID, 0, "Leer desde aquí")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != READ_FROM_SELECTION_ID) return false
        val start = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        onReadFromSelection(view.text?.toString().orEmpty(), start)
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

fun CharSequence.withOptionalHighlight(
    highlightStart: Int?,
    highlightEnd: Int?,
    highlightColor: Int?
): CharSequence {
    val start = highlightStart ?: return this
    val end = highlightEnd ?: return this
    val color = highlightColor ?: return this
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(safeStart, length)
    if (safeStart == safeEnd) return this

    val spannable = SpannableString(this)
    spannable.setSpan(
        BackgroundColorSpan(color),
        safeStart,
        safeEnd,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    return spannable
}
