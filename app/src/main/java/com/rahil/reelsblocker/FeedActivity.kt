package com.rahil.reelsblocker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

/**
 * Instagram's website inside the app, with ads, suggested posts, Reels and the Explore grid
 * removed by a filter script that runs before Instagram's own code on every page — the way a
 * browser ad blocker works, so nothing unwanted is ever drawn. Unlike [BlockerService], this
 * changes what Instagram shows instead of covering or skipping it.
 *
 * Only Instagram (and Meta's login pages) load here; every other link opens in the browser.
 * Voice and video calls open in a window of their own over the page, and only Instagram's
 * pages may use the camera and microphone.
 */
class FeedActivity : AppCompatActivity() {

    private lateinit var frame: FrameLayout
    private lateinit var web: WebView
    private lateinit var refresher: SwipeRefreshLayout
    /** Shown over the page from launch until the first post is on screen. */
    private var launchScreen: View? = null
    private var contentPollStarted = 0L
    /** A call (or any other window Instagram opens), shown over the feed until it closes. */
    private var popup: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    /** Camera/microphone requests waiting on Android's permission dialog (a call asks twice). */
    private val pendingPermissions = mutableListOf<Pair<PermissionRequest, List<String>>>()
    /** Android permissions already asked for while the current requests wait. */
    private val askedPermissions = mutableSetOf<String>()

    /** Where the camera saves a photo or video taken for Instagram (in the app's cache). */
    private val cameraOutputs = mutableListOf<Uri>()
    private var pendingChooser: WebChromeClient.FileChooserParams? = null
    private var askedCamera = false

    /** Photos and videos for Instagram: from the gallery, or taken there and then. */
    private val pickMedia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val clip = data?.clipData
        val picked: List<Uri> = when {
            result.resultCode != RESULT_OK -> emptyList()
            clip != null -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            data?.data != null -> listOf(data.data!!)
            // The camera saves to the file it was given and returns no data: whichever got
            // something written to it.
            else -> cameraOutputs.filter(::hasContent)
        }
        fileCallback?.onReceiveValue(if (picked.isEmpty()) null else picked.toTypedArray())
        fileCallback = null
    }

    /** The camera needs its permission first (the app declares it, for calls). */
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingChooser?.let { launchChooser(it) }
        pendingChooser = null
    }

    /** Camera and microphone, asked for the first time a call needs them. */
    private val askDevices: ActivityResultLauncher<Array<String>> = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // A request that arrived while the dialog was open may need a permission it didn't ask
        // for (a call asks for the microphone, then the camera): that one is asked next.
        val notAsked = pendingPermissions.flatMap { (_, resources) -> resources.map(::androidPermission) }
            .filter { it !in askedPermissions && !hasPermission(it) }.distinct()
        if (notAsked.isNotEmpty()) {
            askedPermissions += notAsked
            askDevices.launch(notAsked.toTypedArray())
            return@registerForActivityResult
        }
        // Every waiting request is answered from what the phone now allows.
        val waiting = pendingPermissions.toList()
        pendingPermissions.clear()
        askedPermissions.clear()
        for ((request, resources) in waiting) {
            val granted = resources.filter { hasPermission(androidPermission(it)) }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Debug builds only: lets Chrome DevTools inspect the page to update the filter.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        // Android 15 draws apps edge to edge: keep the page clear of the status bar, the
        // navigation bar and the keyboard.
        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)

        web = newWebView()
        // Pull down at the top of the feed to refresh, as in the app. Only there: chats, the
        // story viewer and other pages scroll inside the page, where a pull would misfire.
        refresher = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.WHITE)
            setProgressBackgroundColorSchemeColor(Color.rgb(38, 38, 38))
            setOnChildScrollUpCallback { _, _ -> popup != null || web.scrollY > 0 || !onFeed() }
            setOnRefreshListener {
                web.reload()
                waitForContent()
            }
            addView(web, matchParent())
        }
        frame.addView(refresher, matchParent())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = popup
                when {
                    p != null -> if (p.canGoBack()) p.goBack() else closePopup()
                    web.canGoBack() -> web.goBack()
                    // Like the Instagram app, Back on the feed puts it away rather than closing
                    // it, so opening it again is instant instead of loading Instagram afresh.
                    else -> moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            showLaunchScreen()
            web.loadUrl(home())
        }
        waitForContent()
        UpdateChecker.checkIfDue(this)
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
        closePopup()
        web.destroy()
        super.onDestroy()
    }

    /**
     * Android's picker for what Instagram asked for, with the camera offered alongside the
     * gallery: take a photo (or a video, where Instagram takes videos) and it's sent as if
     * picked. Where the page asks for the camera itself, the camera opens straight away.
     */
    private fun launchChooser(params: WebChromeClient.FileChooserParams) {
        val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun accepts(kind: String) = types.isEmpty() || types.any {
            it == "*/*" || it.startsWith("$kind/") ||
                (kind == "image" && it in IMAGE_EXTENSIONS) || (kind == "video" && it in VIDEO_EXTENSIONS)
        }

        cameraOutputs.clear()
        val cameraIntents = mutableListOf<Intent>()
        if (hasPermission(Manifest.permission.CAMERA)) {
            if (accepts("image")) cameraIntents += cameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, "jpg")
            if (accepts("video")) cameraIntents += cameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, "mp4")
        }

        val mimes = types.filter { it.contains('/') }.ifEmpty { listOf("*/*") }
        val gallery = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val intent = if (params.isCaptureEnabled && cameraIntents.isNotEmpty()) {
            cameraIntents.first()
        } else {
            Intent.createChooser(gallery, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
        }
        try {
            pickMedia.launch(intent)
        } catch (e: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }

    /** A camera intent saving to a new file in the app's cache, shared with the camera app. */
    private fun cameraIntent(action: String, extension: String): Intent {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        // Older shots have long since been handed to Instagram (or abandoned).
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000 }?.forEach { it.delete() }
        val file = File(dir, "silentfeed-${System.currentTimeMillis()}.$extension")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraOutputs += uri
        return Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun hasContent(uri: Uri): Boolean =
        runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0 } }.getOrNull() == true

    /** The Following feed (Home) is showing. getUrl follows the page's in-place navigation. */
    private fun onFeed(): Boolean {
        val url = web.url?.let(Uri::parse) ?: return false
        return url.host in FILTER_HOSTS && (url.path.isNullOrEmpty() || url.path == "/")
    }

    /** The app's icon on black, in place of a blank page while Instagram starts up. */
    private fun showLaunchScreen() {
        val size = (96 * resources.displayMetrics.density).toInt()
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_feed)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.22f)
                }
            }
        }
        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        launchScreen = screen
        frame.addView(screen, matchParent())
    }

    /**
     * Checks a few times a second until the page shows a post (or any page other than the
     * feed has drawn), then fades the launch screen and stops the refresh spinner. Gives up
     * after 10s so neither can get stuck.
     */
    private fun waitForContent() {
        contentPollStarted = System.currentTimeMillis()
        pollContent()
    }

    private fun pollContent() {
        if (isDestroyed) return
        val started = contentPollStarted
        web.evaluateJavascript(
            "(function(){var m=document.querySelector('main');return !!(m&&(m.querySelector('article')||(location.pathname!=='/'&&m.childElementCount)))})()"
        ) { ready ->
            if (started != contentPollStarted) return@evaluateJavascript
            if (ready == "true" || System.currentTimeMillis() - started > 10_000) contentShown()
            else web.postDelayed({ pollContent() }, 150)
        }
    }

    private fun contentShown() {
        refresher.isRefreshing = false
        val screen = launchScreen ?: return
        launchScreen = null
        screen.animate().alpha(0f).setDuration(180).withEndAction { frame.removeView(screen) }.start()
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /**
     * Home is Instagram's Following feed: only accounts you follow, newest first, ending in a
     * check mark once you're caught up (see the filter).
     */
    private fun home() = HOME_FOLLOWING

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // The page never needs the app's or the phone's files.
            allowFileAccess = false
            allowContentAccess = false
            // Calls open in a window of their own (only from a tap: see onCreateWindow).
            setSupportMultipleWindows(true)
            // Draws the parts of the page just off screen ahead of time, so scrolling doesn't
            // reveal blank tiles.
            offscreenPreRaster = true
            // Instagram serves a cut-down page to embedded browsers; present as Chrome.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/[0-9.]+"), "")
        }
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Instagram's login cookies are first-party; nothing else gets to set any.
            setAcceptThirdPartyCookies(w, false)
        }
        installFilter(w)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (isInstagram(url)) return false
                // Embedded frames and script-started jumps never leave the app: only a link the
                // user tapped in the page itself opens outside.
                if (request.isForMainFrame && request.hasGesture()) openExternally(url)
                // A new window sent straight somewhere else would be left blank over the feed.
                if (view === popup && request.isForMainFrame && !view.canGoBack()) view.post { if (popup === view) closePopup() }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // A new window's first page doesn't pass through shouldOverrideUrlLoading:
                // anything but Instagram goes to the browser and the window closes.
                val uri = Uri.parse(url)
                if (view === popup && url != "about:blank" && !isInstagram(uri)) {
                    view.stopLoading()
                    // Closed after this callback returns: the window is still in use during it.
                    view.post { if (popup === view) closePopup() }
                    openExternally(uri)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Without document-start scripts the filter goes in as soon as each page loads,
                // on Instagram's own pages only, like the document-start path.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
                if (Uri.parse(url).host in FILTER_HOSTS) view.evaluateJavascript(filterScript(), null)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                if (!hasPermission(Manifest.permission.CAMERA) && !askedCamera) {
                    askedCamera = true
                    pendingChooser = params
                    askCamera.launch(Manifest.permission.CAMERA)
                } else {
                    launchChooser(params)
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) = handleDeviceRequest(request)

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // One extra window at a time (a call), opened only by a tap, under the same rules
                // as the feed.
                // A window opened from the extra window itself is refused rather than replacing
                // it from inside its own callback.
                if (!isUserGesture || view === popup) return false
                closePopup()
                val child = newWebView()
                popup = child
                frame.addView(child, matchParent())
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                if (window === popup) closePopup()
            }
        }
        return w
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        frame.removeView(p)
        p.destroy()
    }

    /**
     * Camera and microphone go only to Instagram's own pages (for calls), and only with the
     * phone's permission, which is asked for the first time a call needs it.
     */
    private fun handleDeviceRequest(request: PermissionRequest) {
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (request.origin.scheme != "https" || request.origin.host !in CALL_HOSTS || wanted.isEmpty()) {
            request.deny()
            return
        }
        val missing = wanted.map(::androidPermission).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            request.grant(wanted.toTypedArray())
            return
        }
        // Android shows one permission dialog at a time: later requests wait for its answer.
        val dialogOpen = pendingPermissions.isNotEmpty()
        pendingPermissions += request to wanted
        if (!dialogOpen) {
            askedPermissions += missing
            askDevices.launch(missing.distinct().toTypedArray())
        }
    }

    private fun androidPermission(resource: String) =
        if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** The filter runs at document start on Instagram's pages only, before any of its scripts. */
    private fun installFilter(w: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, filterScript(), FILTER_HOSTS.map { "https://$it" }.toSet())
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
            // SilentFeed is an ad blocker whatever the switches say: ads and suggested posts are
            // removed before they're drawn, and story ads are never fetched. (Those switches
            // are for the real Instagram app, where they skip and cover instead.)
            .put("hideAds", true)
            .put("hideSuggested", true)
            .put("followingFeed", true)
            .put("blockReels", state.blockReels)
            .put("allowDmReels", state.allowDmReels)
            .put("hideExplore", state.blockExplore)
            .put("skipStoryAds", true)
            .put("debug", state.debugMode)
            // The Android app (calls and the emoji fix are on; see the filter).
            .put("app", true)
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
        private const val HOME_FOLLOWING = "https://www.instagram.com/?variant=following"
        private const val FILTER_ASSET = "web_filter.js"
        private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp", ".gif")
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".mov", ".webm", ".m4v")
        // Pages that may load in the app: Instagram's site and Meta's login and account pages.
        // (Images and videos come from Instagram's CDN as subresources, which don't need this.)
        private val ALLOWED_HOSTS = listOf("instagram.com", "facebook.com", "accountscenter.meta.com")
        private val FILTER_HOSTS = setOf("www.instagram.com", "instagram.com", "call.instagram.com")
        // Pages that may use the camera and microphone, for calls.
        private val CALL_HOSTS = FILTER_HOSTS
    }
}
