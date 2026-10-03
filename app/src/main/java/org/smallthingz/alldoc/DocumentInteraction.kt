package org.smallthingz.alldoc

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Live providers avoid the one-frame lag of copying fling/selection state into a parent Boolean.
 */
internal class DocumentInteractionState {
    private val motions = linkedMapOf<Any, () -> Boolean>()
    private val selections = linkedMapOf<Any, () -> Boolean>()
    private var lastNativeMotion = Long.MIN_VALUE
    var onTextEdited: () -> Unit = {}

    fun moving(): Boolean =
        motions.values.any { it() } || SystemClock.uptimeMillis() - lastNativeMotion in 0..160

    fun selecting(): Boolean = selections.values.any { it() }

    fun nativeMotion() {
        lastNativeMotion = SystemClock.uptimeMillis()
    }

    fun motion(key: Any, value: (() -> Boolean)?) {
        if (value == null) motions.remove(key) else motions[key] = value
    }

    fun selection(key: Any, value: (() -> Boolean)?) {
        if (value == null) selections.remove(key) else selections[key] = value
    }
}

internal val LocalDocumentInteraction = staticCompositionLocalOf<DocumentInteractionState?> { null }

@Composable
internal fun RegisterDocumentMotion(key: Any, provider: () -> Boolean) {
    val state = LocalDocumentInteraction.current
    val latest by rememberUpdatedState(provider)
    DisposableEffect(state, key) {
        state?.motion(key) { latest() }
        onDispose { state?.motion(key, null) }
    }
}

@Composable
internal fun RegisterDocumentScroll(scroll: ScrollState) =
    RegisterDocumentMotion(scroll) { scroll.isScrollInProgress }

@Composable
internal fun RegisterDocumentScroll(scroll: LazyListState) =
    RegisterDocumentMotion(scroll) { scroll.isScrollInProgress }

/** A normal text field whose live selection state is available to the non-consuming reveal gate. */
@Composable
internal fun DocumentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    cursorBrush: Brush = SolidColor(Color.Black),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    searchQuery: String = "",
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val interactions = LocalDocumentInteraction.current
    val token = remember { Any() }
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            val index = value.indexOf(searchQuery, ignoreCase = true)
            if (index >= 0)
                field =
                    field.copy(
                        text = value,
                        selection = TextRange(index, index + searchQuery.length),
                    )
        }
    }
    val displayed = if (field.text == value) field else field.copy(text = value)
    DisposableEffect(interactions, token) {
        interactions?.selection(token) { focused && !field.selection.collapsed }
        onDispose { interactions?.selection(token, null) }
    }
    BasicTextField(
        value = displayed,
        onValueChange = { next ->
            field = next
            if (next.text != value) {
                interactions?.onTextEdited?.invoke()
                onValueChange(next.text)
            }
        },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        minLines = minLines,
        maxLines = maxLines,
        cursorBrush = cursorBrush,
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
    )
}
