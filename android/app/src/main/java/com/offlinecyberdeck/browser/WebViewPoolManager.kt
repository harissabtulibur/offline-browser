package com.offlinecyberdeck.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

data class WebViewCallbacks(
    val onPageStarted: (tabId: String, url: String) -> Unit,
    val onPageFinished: (tabId: String, url: String, title: String, canBack: Boolean, canForward: Boolean) -> Unit,
    val onProgressChanged: (tabId: String, progress: Int) -> Unit,
    val onTitleChanged: (tabId: String, title: String) -> Unit,
    val onSslErrorBlocked: (tabId: String, url: String, reason: String) -> Unit,
    val onExternalSchemeRequested: (tabId: String, uriString: String) -> Unit,
    val onDownloadRequested: (url: String, fileName: String, mimeType: String) -> Unit,
    val onSitePermissionPrompt: (tabId: String, origin: String, resources: List<String>, request: PermissionRequest) -> Unit,
    val onFindMatchesUpdated: (tabId: String, activeOrdinal: Int, count: Int) -> Unit
)

class WebViewPoolManager(
    private val context: Context,
    private val callbacks: WebViewCallbacks
) {
    private val liveViews = LinkedHashMap<String, WebView>(4, 0.75f, true)
    private val savedBundles = HashMap<String, Bundle>()
    private var maxLiveCount = 3

    fun setMaxLiveCount(limit: Int) {
        maxLiveCount = limit.coerceIn(1, 5)
        evictExcess(activeTabId = null)
    }

    fun getOrCreateWebView(
        tabId: String,
        url: String,
        isIncognito: Boolean,
        desktopMode: Boolean,
        javaScriptEnabled: Boolean,
        forceDarkWeb: Boolean
    ): WebView {
        val existing = liveViews[tabId]
        if (existing != null) {
            applyRuntimeSettings(existing, desktopMode, javaScriptEnabled, forceDarkWeb)
            existing.onResume()
            return existing
        }

        evictExcess(activeTabId = tabId)

        val webView = createConfiguredWebView(
            tabId = tabId,
            isIncognito = isIncognito,
            desktopMode = desktopMode,
            javaScriptEnabled = javaScriptEnabled,
            forceDarkWeb = forceDarkWeb
        )
        liveViews[tabId] = webView

        val saved = savedBundles.remove(tabId)
        if (saved != null) {
            webView.restoreState(saved)
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            webView.loadUrl(url)
        }
        return webView
    }

    fun getLiveWebView(tabId: String): WebView? = liveViews[tabId]

    fun pauseInactiveTabs(activeTabId: String) {
        liveViews.forEach { (id, view) ->
            if (id != activeTabId) {
                view.onPause()
            } else {
                view.onResume()
            }
        }
    }

    fun destroyTab(tabId: String, isIncognito: Boolean) {
        savedBundles.remove(tabId)
        val view = liveViews.remove(tabId) ?: return
        destroyWebViewSafely(view, clearPrivateData = isIncognito)
    }

    fun clearAllIncognitoSessionData() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    fun destroyAll() {
        liveViews.values.forEach { destroyWebViewSafely(it, clearPrivateData = false) }
        liveViews.clear()
        savedBundles.clear()
    }

    private fun evictExcess(activeTabId: String?) {
        while (liveViews.size >= maxLiveCount) {
            val victimKey = liveViews.keys.firstOrNull { it != activeTabId } ?: break
            val victim = liveViews.remove(victimKey) ?: break
            val bundle = Bundle()
            runCatching { victim.saveState(bundle) }
            if (!bundle.isEmpty) {
                savedBundles[victimKey] = bundle
            }
            destroyWebViewSafely(victim, clearPrivateData = false)
        }
    }

    private fun destroyWebViewSafely(webView: WebView, clearPrivateData: Boolean) {
        runCatching {
            webView.stopLoading()
            webView.onPause()
            if (clearPrivateData) {
                webView.clearHistory()
                webView.clearCache(true)
                webView.clearFormData()
            }
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        tabId: String,
        isIncognito: Boolean,
        desktopMode: Boolean,
        javaScriptEnabled: Boolean,
        forceDarkWeb: Boolean
    ): WebView {
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                this.javaScriptEnabled = javaScriptEnabled
                domStorageEnabled = !isIncognito
                databaseEnabled = !isIncognito
                saveFormData = !isIncognito
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportMultipleWindows(false)
                safeBrowsingEnabled = true
                cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            }

            applyRuntimeSettings(this, desktopMode, javaScriptEnabled, forceDarkWeb)

            if (isIncognito) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            }

            setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                if (isDoneCounting) {
                    callbacks.onFindMatchesUpdated(tabId, activeMatchOrdinal, numberOfMatches)
                }
            }

            setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    callbacks.onDownloadRequested(url, fileName, mimeType ?: "application/octet-stream")
                }
            })

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https" || scheme == "about") {
                        return false
                    }
                    callbacks.onExternalSchemeRequested(tabId, uri.toString())
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) {
                        callbacks.onPageStarted(tabId, url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view != null && !url.isNullOrBlank()) {
                        callbacks.onPageFinished(
                            tabId,
                            url,
                            view.title.orEmpty().ifBlank { url },
                            view.canGoBack(),
                            view.canGoForward()
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Security-first: never proceed past an invalid SSL certificate
                    handler?.cancel()
                    val reason = when (error?.primaryError) {
                        SslError.SSL_EXPIRED -> "Certificate expired"
                        SslError.SSL_IDMISMATCH -> "Hostname mismatch"
                        SslError.SSL_UNTRUSTED -> "Untrusted certificate authority"
                        SslError.SSL_NOTYETVALID -> "Certificate not yet valid"
                        else -> "Invalid SSL/TLS certificate"
                    }
                    callbacks.onSslErrorBlocked(tabId, error?.url.orEmpty(), reason)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    callbacks.onProgressChanged(tabId, newProgress)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) {
                        callbacks.onTitleChanged(tabId, title)
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    val origin = request.origin?.toString().orEmpty()
                    val res = request.resources?.toList().orEmpty()
                    // Never silently grant sensitive permissions
                    callbacks.onSitePermissionPrompt(tabId, origin, res, request)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyRuntimeSettings(
        webView: WebView,
        desktopMode: Boolean,
        javaScriptEnabled: Boolean,
        forceDarkWeb: Boolean
    ) {
        webView.settings.javaScriptEnabled = javaScriptEnabled
        val defaultUa = WebSettings.getDefaultUserAgent(context)
        webView.settings.userAgentString = if (desktopMode) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        } else {
            defaultUa
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, forceDarkWeb)
        }
    }
}
