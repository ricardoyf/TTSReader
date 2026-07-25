package com.ricardo.txtreader.ui.components

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownText(
    markdown: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    highlightStart: Int? = null,
    highlightEnd: Int? = null,
    highlightColor: Int? = null,
    onTextViewReady: (TextView) -> Unit = {},
    onReadFromSelection: (String, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                customSelectionActionModeCallback = readFromSelectionCallback(this, onReadFromSelection)
            }
        },
        update = { view ->
            view.customSelectionActionModeCallback = readFromSelectionCallback(view, onReadFromSelection)
            markwon.setMarkdown(view, markdown)
            view.textSize = fontSize.value
            view.text = view.text.withOptionalHighlight(highlightStart, highlightEnd, highlightColor)
            view.post { onTextViewReady(view) }
        }
    )
}
