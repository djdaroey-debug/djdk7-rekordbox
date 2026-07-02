package fr.daroey.djdk7

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * DJ DK7 — lecteur Rekordbox.
 * La page web (assets/index.html) est servie sous https://dk7.local et lit la carte via
 *   /card/list           -> liste JSON des fichiers
 *   /card/file/<chemin>   -> contenu du fichier (avec support Range pour le streaming audio)
 * L'accès à la carte se fait via le sélecteur de dossier Android (SAF), mémorisé.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var treeUri: Uri? = null
    // cache: chemin relatif (minuscule) -> Uri du document
    private var fileCache: MutableMap<String, Uri>? = null
    private var fileList: MutableList<String>? = null

    private lateinit var pickTree: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                getSharedPreferences("dk7", Context.MODE_PRIVATE).edit()
                    .putString("tree", uri.toString()).apply()
                treeUri = uri
                fileCache = null; fileList = null
                web.post { web.evaluateJavascript("window.onCardReady && window.onCardReady();", null) }
            }
        }

        // restaure la carte mémorisée
        getSharedPreferences("dk7", Context.MODE_PRIVATE).getString("tree", null)?.let {
            treeUri = Uri.parse(it)
        }

        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(), "DK7")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != "dk7.local") return null
                val path = u.path ?: return null
                return try {
                    when {
                        path == "/" || path == "/index.html" -> asset("index.html", "text/html")
                        path == "/card/list" -> serveList()
                        path.startsWith("/card/file/") ->
                            serveFile(Uri.decode(path.removePrefix("/card/file/")), request.requestHeaders["Range"])
                        else -> null
                    }
                } catch (e: Exception) {
                    resp("text/plain", 500, "err", ByteArrayInputStream(("" + e.message).toByteArray()), null)
                }
            }
        }
        web.loadUrl("https://dk7.local/index.html")
    }

    inner class Bridge {
        @JavascriptInterface fun pickCard() { runOnUiThread { pickTree.launch(null) } }
        @JavascriptInterface fun hasCard(): Boolean = treeUri != null
    }

    // --- service des fichiers ------------------------------------------------

    private fun asset(name: String, mime: String): WebResourceResponse {
        val stream = assets.open(name)
        return resp(mime, 200, "OK", stream, mapOf("Access-Control-Allow-Origin" to "*"))
    }

    private fun buildCache() {
        val cache = HashMap<String, Uri>()
        val list = ArrayList<String>()
        val tree = treeUri ?: run { fileCache = cache; fileList = list; return }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun recurse(docId: String, prefix: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1); val mime = c.getString(2)
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) recurse(id, rel)
                    else {
                        list.add(rel)
                        cache[rel.lowercase()] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        try { recurse(rootId, "") } catch (_: Exception) {}
        fileCache = cache; fileList = list
    }

    private fun serveList(): WebResourceResponse {
        if (fileList == null) buildCache()
        val arr = JSONArray()
        fileList?.forEach { arr.put(JSONObject().put("path", it)) }
        return resp(
            "application/json", 200, "OK",
            ByteArrayInputStream(arr.toString().toByteArray()),
            mapOf("Access-Control-Allow-Origin" to "*")
        )
    }

    private fun mimeFor(p: String): String {
        val l = p.lowercase()
        return when {
            l.endsWith(".mp3") -> "audio/mpeg"
            l.endsWith(".m4a") || l.endsWith(".aac") -> "audio/mp4"
            l.endsWith(".flac") -> "audio/flac"
            l.endsWith(".wav") -> "audio/wav"
            l.endsWith(".ogg") -> "audio/ogg"
            l.endsWith(".aif") || l.endsWith(".aiff") -> "audio/aiff"
            else -> "application/octet-stream"
        }
    }

    private fun serveFile(rel: String, range: String?): WebResourceResponse {
        if (fileCache == null) buildCache()
        val uri = fileCache?.get(rel.lowercase())
            ?: return resp("text/plain", 404, "NF", ByteArrayInputStream(ByteArray(0)), null)
        val mime = mimeFor(rel)
        val total = fileSize(uri)
        val headers = HashMap<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Accept-Ranges"] = "bytes"

        if (range != null && total > 0) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
            if (m != null) {
                val start = m.groupValues[1].toLong()
                val end = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toLong() else total - 1
                val len = (end - start + 1).coerceAtLeast(0)
                val base = contentResolver.openInputStream(uri) ?: return resp("text/plain", 404, "NF", ByteArrayInputStream(ByteArray(0)), null)
                skipFully(base, start)
                val limited = LimitedInputStream(base, len)
                headers["Content-Range"] = "bytes $start-$end/$total"
                headers["Content-Length"] = len.toString()
                return resp(mime, 206, "Partial Content", limited, headers)
            }
        }
        val stream = contentResolver.openInputStream(uri)
            ?: return resp("text/plain", 404, "NF", ByteArrayInputStream(ByteArray(0)), null)
        if (total > 0) headers["Content-Length"] = total.toString()
        return resp(mime, 200, "OK", stream, headers)
    }

    private fun fileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = s.skip(left)
            if (skipped > 0) { left -= skipped; continue }
            val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    private fun resp(mime: String, code: Int, reason: String, data: InputStream, headers: Map<String, String>?): WebResourceResponse {
        val r = WebResourceResponse(mime, "UTF-8", data)
        r.setStatusCodeAndReasonPhrase(code, reason)
        if (headers != null) r.responseHeaders = headers
        return r
    }

    override fun onBackPressed() {
        // ferme la page Paramètres si ouverte, sinon quitte
        web.evaluateJavascript(
            "(function(){var p=document.getElementById('setPage');var m=document.getElementById('modal');" +
            "if(m&&m.classList.contains('show')){m.classList.remove('show');return 'x';}" +
            "if(p&&p.classList.contains('show')){p.classList.remove('show');return 'x';}return '';})();"
        ) { v -> if (v == null || v == "\"\"" || v == "null") runOnUiThread { finish() } }
    }
}

private class LimitedInputStream(base: InputStream, private var remaining: Long) : FilterInputStream(base) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read(); if (b >= 0) remaining--
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val r = super.read(b, off, toRead)
        if (r > 0) remaining -= r
        return r
    }
}
