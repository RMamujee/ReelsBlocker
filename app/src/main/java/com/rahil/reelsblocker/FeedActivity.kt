package com.rahil.reelsblocker

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Instagram's website inside the app, with ads, suggested posts, Reels and the Explore grid
 * removed by a filter script that runs before Instagram's own code on every page — the way a
 * browser ad blocker works, so nothing unwanted is ever drawn. Unlike [BlockerService], this
 * changes what Instagram shows instead of covering or skipping it.
 *
 * Only Instagram (and Meta's login pages) load here; every other link opens in the browser.
 */
class FeedActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Debug builds only: lets Chrome DevTools inspect the page to update the filter.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        // Android 15 draws apps edge to edge: keep the page clear of the status bar, the
        // navigation bar and the keyboard.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // The page never needs the app's or the phone's files.
            allowFileAccess = false
            allowContentAccess = false
            // Instagram serves a cut-down page to embedded browsers; present as Chrome.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/[0-9.]+"), "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Instagram's login cookies are first-party; nothing else gets to set any.
            setAcceptThirdPartyCookies(web, false)
        }

        installFilter()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (isInstagram(url)) return false
                // Embedded frames and script-started jumps never leave the app: only a link the
                // user tapped in the page itself opens outside.
                if (request.isForMainFrame && request.hasGesture()) openExternally(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Without document-start scripts the filter goes in as soon as each page loads,
                // on Instagram's own pages only, like the document-start path.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
                if (Uri.parse(url).host in FILTER_HOSTS) view.evaluateJavascript(filterScript(), null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                return try {
                    pickFiles.launch(type)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            // No camera or microphone for the website.
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** The filter runs at document start on Instagram's pages only, before any of its scripts. */
    private fun installFilter() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, filterScript(), FILTER_HOSTS.map { "https://$it" }.toSet())
        } else {
            Log.w(TAG, "WebView without document-start scripts: filter applied after page load")
        }
    }

    /**
     * The filter with the user's switches from the main screen, passed in as a closure
     * variable so Instagram's own scripts can't read them.
     */
    private fun filterScript(): String {
        val state = BlockerState(this)
        val config = JSONObject()
            .put("hideAds", state.hideFeedAds)
            .put("hideSuggested", state.hideSuggestedPosts)
            .put("blockReels", state.blockReels)
            .put("allowDmReels", state.allowDmReels)
            .put("hideExplore", state.blockExplore)
            .put("skipStoryAds", state.skipStoryAds)
            .put("debug", state.debugMode)
        val script = assets.open(FILTER_ASSET).bufferedReader().use { it.readText() }
        return "(function () {\nvar __rbCfg = $config;\n$script\n})();"
    }

    private fun isInstagram(url: Uri): Boolean {
        if (url.scheme != "https") return false
        val host = url.host ?: return false
        // Instagram's redirect for outside links: those open in the browser.
        if (host == "l.instagram.com") return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Opens a web link in the browser. Instagram wraps outside links in a redirect, so the
     * real target is unwrapped, and then checked again: only http(s) links ever leave the
     * app, as a browsable link, so a crafted link can't launch some other app's screen.
     */
    private fun openExternally(url: Uri) {
        val target = if (url.host == "l.instagram.com") url.getQueryParameter("u")?.let(Uri::parse) ?: url else url
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "Refused to open a non-web link")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser to open a link")
        }
    }

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val HOME = "https://www.instagram.com/"
        private const val FILTER_ASSET = "web_filter.js"
        // Pages that may load in the app: Instagram's site and Meta's login and account pages.
        // (Images and videos come from Instagram's CDN as subresources, which don't need this.)
        private val ALLOWED_HOSTS = listOf("instagram.com", "facebook.com", "accountscenter.meta.com")
        private val FILTER_HOSTS = setOf("www.instagram.com", "instagram.com")
    }
}
