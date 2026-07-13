package fr.daroey.djdk7

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * DJ DK7 — lecteur Rekordbox.
 *
 * La carte est lue via un ou plusieurs dossiers autorisés (SAF). Sur certaines cartes SD,
 * Android interdit de sélectionner la racine ; on choisit donc les dossiers un par un :
 *   1) le dossier PIONEER (playlists)  2) le dossier Contents (musiques).
 * Les autorisations sont mémorisées, les fichiers de tous les dossiers sont combinés.
 *
 * Les fichiers sont exposés à la page web sous https://dk7.local :
 *   /card/list            -> liste JSON (chemins préfixés par le nom du dossier racine)
 *   /card/file/<chemin>   -> contenu du fichier (avec support Range pour le streaming audio)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val trees = ArrayList<Uri>()                 // dossiers autorisés (PIONEER, Contents, ...)
    private var fileCache: MutableMap<String, Uri>? = null
    private var fileList: MutableList<String>? = null
    private lateinit var pickTree: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                addTree(uri)
                saveTrees()
                fileCache = null; fileList = null
                val name = treeName(uri)
                toast("Dossier « $name » ajouté")
                if (name.equals("PIONEER", true) && trees.none { treeName(it).equals("Contents", true) }) {
                    toast("Maintenant rappuie sur « Carte » et choisis le dossier Contents")
                }
                web.post { web.evaluateJavascript("window.onCardReady && window.onCardReady();", null) }
            }
        }

        loadTrees()

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
        @JavascriptInterface fun hasCard(): Boolean = trees.isNotEmpty()
        @JavascriptInterface fun resetCard() {
            runOnUiThread {
                trees.clear(); saveTrees(); fileCache = null; fileList = null
                toast("Carte réinitialisée — rechoisis PIONEER puis Contents")
                web.evaluateJavascript("window.onCardReady && window.onCardReady();", null)
            }
        }
    }

    // --- gestion des dossiers autorisés -------------------------------------

    private fun addTree(uri: Uri) {
        if (trees.none { it.toString() == uri.toString() }) trees.add(uri)
    }

    private fun treeName(uri: Uri): String =
        try { DocumentFile.fromTreeUri(this, uri)?.name ?: "" } catch (_: Exception) { "" }

    private fun saveTrees() {
        getSharedPreferences("dk7", Context.MODE_PRIVATE).edit()
            .putStringSet("trees", trees.map { it.toString() }.toSet()).apply()
    }

    private fun loadTrees() {
        trees.clear()
        getSharedPreferences("dk7", Context.MODE_PRIVATE)
            .getStringSet("trees", emptySet())?.forEach { trees.add(Uri.parse(it)) }
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    // --- construction de l'index des fichiers -------------------------------

    private fun buildCache() {
        val cache = HashMap<String, Uri>()
        val list = ArrayList<String>()
        for (tree in trees) {
            try {
                val prefix = treeName(tree)                       // "PIONEER", "Contents", ...
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                walk(tree, rootId, prefix, cache, list)
            } catch (_: Exception) {}
        }
        fileCache = cache; fileList = list
    }

    private fun walk(tree: Uri, docId: String, prefix: String,
                     cache: MutableMap<String, Uri>, list: MutableList<String>) {
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
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, rel, cache, list)
                } else {
                    list.add(rel)
                    cache[rel.lowercase()] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
    }

    // --- service HTTP simulé -------------------------------------------------

    private fun asset(name: String, mime: String): WebResourceResponse {
        val stream = assets.open(name)
        return resp(mime, 200, "OK", stream, mapOf("Access-Control-Allow-Origin" to "*"))
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
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: return resp("text/plain", 404, "NF", ByteArrayInputStream(ByteArray(0)), null)
                val ins = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                try { ins.channel.position(start) } catch (_: Exception) {}
                val limited = LimitedInputStream(ins, len)
                headers["Content-Range"] = "bytes $start-$end/$total"
                headers["Content-Length"] = len.toString()
                return resp(mime, 206, "Partial Content", limited, headers)
            }
        }
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: return resp("text/plain", 404, "NF", ByteArrayInputStream(ByteArray(0)), null)
        val ins = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (total > 0) headers["Content-Length"] = total.toString()
        return resp(mime, 200, "OK", ins, headers)
    }

    private fun fileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }


    private fun resp(mime: String, code: Int, reason: String, data: InputStream, headers: Map<String, String>?): WebResourceResponse {
        val r = WebResourceResponse(mime, "UTF-8", data)
        r.setStatusCodeAndReasonPhrase(code, reason)
        if (headers != null) r.responseHeaders = headers
        return r
    }

    override fun onBackPressed() {
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
