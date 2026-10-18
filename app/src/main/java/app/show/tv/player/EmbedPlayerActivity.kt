package app.show.tv.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.show.tv.data.Embeds
import app.show.tv.ui.components.ShowPrimaryButton
import app.show.tv.ui.components.ShowSecondaryButton
import app.show.tv.ui.theme.ShowColors
import app.show.tv.ui.theme.ShowTheme

/**
 * Plays a YouTube or Dailymotion live channel in the platform's own embedded player (1.5.4).
 *
 * Same lifecycle rules as [PlayerActivity]: nothing plays or loads off-screen (the page is
 * unloaded in onStop and reloaded at the live edge in onStart), the screen stays awake only while
 * the player reports it's playing, and the WebView is destroyed with the activity.
 *
 * Hardening: only the wrapper page and the two platforms' hosts load in the frame; any top-level
 * navigation elsewhere (a tapped logo, an ad) goes to the user's browser or the platform's app.
 * No file or content access, no mixed content, Safe Browsing on, no third-party cookies.
 */
class EmbedPlayerActivity : ComponentActivity() {
    private var web: WebView? = null
    private var target: Embeds.Target? = null
    private var error by mutableStateOf<String?>(null)
    private var title by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Embeds.parse(intent.getStringExtra(EXTRA_URL))
        if (t == null) { finish(); return }
        target = t
        title = intent.getStringExtra(EXTRA_NAME).orEmpty()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            ShowTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx -> createWebView(ctx).also { web = it; load() } }, modifier = Modifier.fillMaxSize())
                    IconButton(onClick = { finish() }, modifier = Modifier.padding(12.dp).align(Alignment.TopStart).testTag("embed_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    error?.let { msg -> ErrorCard(msg) }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ErrorCard(msg: String) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = ShowColors.Mist, textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("embed_error"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShowPrimaryButton(onClick = { error = null; load() }) { Text("Try again") }
                    target?.let { t -> ShowSecondaryButton(onClick = { openExternally(t.watchUrl) }) { Text("Open in ${t.kind.label}") } }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context): WebView = WebView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.apply {
            javaScriptEnabled = true // required by both platforms' players
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        // Only ints cross this bridge; it changes nothing but the keep-screen-on flag and the error card.
        addJavascriptInterface(Bridge(), "ShowEmbed")
        webChromeClient = FullscreenChrome()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                openExternally(request.url.toString()) // never navigate the player away from the wrapper
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (request.isForMainFrame || request.url.host?.let(::isPlayerHost) == true && request.url.path?.contains("embed") == true)
                    showError("Couldn't reach ${target?.kind?.label ?: "the player"}. Check your connection.")
            }
        }
    }

    private fun isPlayerHost(h: String) = h == "www.youtube.com" || h == "geo.dailymotion.com"

    private fun load() {
        val t = target ?: return
        web?.loadDataWithBaseURL(Embeds.ORIGIN + "/", Embeds.html(t), "text/html", "utf-8", null)
    }

    override fun onStart() {
        super.onStart()
        // First start: the WebView doesn't exist yet and its factory loads. Later starts: reconnect at the live edge.
        web?.let { it.onResume(); it.resumeTimers(); if (error == null) load() }
    }

    override fun onStop() {
        // Off-screen = nothing plays or polls: pause, then unload the page entirely.
        web?.evaluateJavascript("showPause()", null)
        web?.loadUrl("about:blank")
        web?.onPause()
        web?.pauseTimers()
        keepAwake(false)
        super.onStop()
    }

    override fun onDestroy() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.removeJavascriptInterface("ShowEmbed"); it.destroy() }
        web = null
        super.onDestroy()
    }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(msg: String) = runOnUiThread { keepAwake(false); error = msg }

    private fun openExternally(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { }
    }

    private inner class Bridge {
        /** YT.PlayerState: 1 playing, 3 buffering keep the screen on; anything else lets it sleep. */
        @JavascriptInterface fun state(s: Int) = runOnUiThread { keepAwake(s == 1 || s == 3) }
        @JavascriptInterface fun error(code: Int) = showError(Embeds.errorText(code))
    }

    /** The player's own fullscreen button: swap in its view over everything. */
    private inner class FullscreenChrome : WebChromeClient() {
        private var custom: View? = null
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            custom?.let { callback.onCustomViewHidden(); return }
            custom = view
            (window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        override fun onHideCustomView() {
            custom?.let { (window.decorView as ViewGroup).removeView(it) }
            custom = null
        }
    }

    companion object {
        private const val EXTRA_URL = "embed_url"
        private const val EXTRA_NAME = "embed_name"
        fun intent(context: Context, url: String, name: String) =
            Intent(context, EmbedPlayerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
    }
}
