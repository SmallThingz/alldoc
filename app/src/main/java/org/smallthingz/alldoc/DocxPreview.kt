package org.smallthingz.alldoc

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.smallthingz.alldoc.core.DocumentEngine

/** Offline, read-only formatted DOCX preview. The WebView has no app bridge or file access. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocxPreview(
  session: DocumentEngine.Session,
  edits: Map<String, String>,
  modifier: Modifier = Modifier,
) {
  var bytes by remember(session, edits) { mutableStateOf<ByteArray?>(null) }
  var error by remember(session, edits) { mutableStateOf<String?>(null) }
  LaunchedEffect(session, edits) {
    try {
      bytes = withContext(Dispatchers.IO) { session.previewBytes(edits) }
    } catch (e: Exception) {
      error = e.message
    }
  }
  val interactions = LocalDocumentInteraction.current
  val fallbackColor = MaterialTheme.colorScheme.onSurface.toArgb()
  Column(modifier) {
    error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
    if (bytes == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error == null)
      bytes?.let { content ->
        key(content) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
              try {
                SelectionAwareDocumentWebView(context,interactions).apply {
                  settings.javaScriptEnabled = true
                  settings.allowFileAccess = false
                  settings.allowContentAccess = false
                  settings.domStorageEnabled = false
                  settings.databaseEnabled = false
                  settings.javaScriptCanOpenWindowsAutomatically = false
                  settings.setSupportMultipleWindows(false)
                  settings.setGeolocationEnabled(false)
                  settings.safeBrowsingEnabled = true
                  webChromeClient =
                    object : WebChromeClient() {
                      override fun onPermissionRequest(request: PermissionRequest) {
                        request.deny()
                      }

                      override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: GeolocationPermissions.Callback,
                      ) {
                        callback.invoke(origin, false, false)
                      }
                    }
                  settings.setSupportZoom(true)
                  settings.builtInZoomControls = true
                  settings.displayZoomControls = false
                  settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                  settings.cacheMode = WebSettings.LOAD_NO_CACHE
                  settings.mediaPlaybackRequiresUserGesture = true
                  webViewClient =
                    object : WebViewClient() {
                      override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                      ): Boolean {
                        error =
                          "Formatted preview stopped. Reveal controls and choose Edit text; your original is unchanged."
                        return true
                      }

                      override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                      ) = true

                      override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                      ): WebResourceResponse {
                        val uri = request.url
                        if (uri.scheme != "https" || uri.host != "alldoc.invalid") return blocked()
                        return when (uri.path) {
                          "/document.docx" ->
                            WebResourceResponse(
                              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                              null,
                              ByteArrayInputStream(content),
                            )
                          "/preview/index.html",
                          "/preview/jszip.min.js",
                          "/preview/docx-preview.min.js",
                          "/preview/preview.js" -> {
                            val type =
                              if (uri.path!!.endsWith(".html")) "text/html" else "text/javascript"
                            WebResourceResponse(
                              type,
                              "utf-8",
                              context.assets.open(uri.path!!.removePrefix("/")),
                            )
                          }
                          else -> blocked()
                        }
                      }

                      private fun blocked() =
                        WebResourceResponse(
                          "text/plain",
                          "utf-8",
                          403,
                          "Blocked",
                          emptyMap(),
                          ByteArrayInputStream(byteArrayOf()),
                        )
                    }
                  loadUrl("https://alldoc.invalid/preview/index.html")
                }
              } catch (failure: Exception) {
                android.widget.TextView(context).apply {
                  text =
                    "Formatted preview is unavailable on this device. Reveal controls and choose Edit text to inspect the document."
                  setTextColor(fallbackColor)
                  setPadding(24, 24, 24, 24)
                }
              }
            },
            onRelease = { view ->
              if (view is WebView)
                runCatching {
                  view.stopLoading()
                  view.destroy()
                }
            },
          )
        }
      }
  }
}

private class SelectionAwareDocumentWebView(context: android.content.Context,private val interaction:DocumentInteractionState?):WebView(context) {
    private var actionModes=0
    init {
        interaction?.selection(this) { actionModes>0 }
        setOnScrollChangeListener { _,_,_,_,_->interaction?.nativeMotion() }
    }
    override fun startActionMode(callback:android.view.ActionMode.Callback):android.view.ActionMode? = startActionMode(callback,android.view.ActionMode.TYPE_PRIMARY)
    override fun startActionMode(callback:android.view.ActionMode.Callback,type:Int):android.view.ActionMode? {
        val wrapped=object:android.view.ActionMode.Callback2() {
            override fun onCreateActionMode(mode:android.view.ActionMode,menu:android.view.Menu):Boolean {
                val created=callback.onCreateActionMode(mode,menu);if(created)actionModes++;return created
            }
            override fun onPrepareActionMode(mode:android.view.ActionMode,menu:android.view.Menu)=callback.onPrepareActionMode(mode,menu)
            override fun onActionItemClicked(mode:android.view.ActionMode,item:android.view.MenuItem)=callback.onActionItemClicked(mode,item)
            override fun onDestroyActionMode(mode:android.view.ActionMode) {try {callback.onDestroyActionMode(mode)} finally {actionModes=(actionModes-1).coerceAtLeast(0)}}
            override fun onGetContentRect(mode:android.view.ActionMode,view:android.view.View,outRect:android.graphics.Rect) {
                if(callback is android.view.ActionMode.Callback2)callback.onGetContentRect(mode,view,outRect) else outRect.set(0,0,view.width,view.height)
            }
        }
        return super.startActionMode(wrapped,type)
    }
    override fun onDetachedFromWindow() {interaction?.selection(this,null);super.onDetachedFromWindow()}
}
