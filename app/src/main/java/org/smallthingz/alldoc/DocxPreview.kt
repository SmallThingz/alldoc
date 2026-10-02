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
  val fallbackColor = MaterialTheme.colorScheme.onSurface.toArgb()
  Column(modifier) {
    Text(
      "Formatted preview · pagination may differ · pinch to zoom",
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
      color = MaterialTheme.colorScheme.secondary,
    )
    error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
    if (bytes == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error == null)
      bytes?.let { content ->
        key(content) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
              try {
                WebView(context).apply {
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
                          "Formatted preview stopped. Switch to Text; your original is unchanged."
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
                    "Formatted preview is unavailable on this device. Switch to Text to read or edit the document."
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
