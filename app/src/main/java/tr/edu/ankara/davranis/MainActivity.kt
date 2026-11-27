package tr.edu.ankara.davranis

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.common.*
import com.google.mlkit.vision.digitalink.recognition.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Davranış Kliniği formunu tablette açan uygulama.
 * - Form, uygulamanın içindeki assets/index.html dosyasıdır.
 * - Kayıt, tablo ve sesle doldurma için Apps Script adresine bağlanır (Ayarlar).
 * - El yazısını Google ML Kit ile cihaz içinde, internetsiz tanır.
 * - Görüşmeyi Android'in konuşma tanıma servisiyle Türkçe yazıya döker; ses kaydedilmez.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var reply: JavaScriptReplyProxy? = null
    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    // El yazısı tanıma
    private val inkModel: DigitalInkRecognitionModel by lazy {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("tr")
            ?: throw IllegalStateException("Türkçe el yazısı modeli bulunamadı")
        DigitalInkRecognitionModel.builder(id).build()
    }
    private var recognizer: DigitalInkRecognizer? = null

    // Konuşma tanıma
    private var speech: SpeechRecognizer? = null
    private var listening = false
    private var pendingSpeechId = -1

    // Kamera / dosya seçici
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        fileCallback?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
        fileCallback = null
    }
    private val pickContent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingSpeechId
        pendingSpeechId = -1
        if (granted) startListening(id) else answer(id, false, error = "Mikrofon izni verilmedi")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                // Drive belgeleri gibi dış bağlantılar tarayıcıda açılır
                startActivity(Intent(Intent.ACTION_VIEW, u))
                return true
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
                return try {
                    if (params.isCaptureEnabled) {
                        val f = File(cacheDir, "etiket_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                        photoUri = uri
                        takePicture.launch(uri)
                    } else {
                        pickContent.launch(params.createIntent())
                    }
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                web, "AndroidBridge", setOf("https://appassets.androidplatform.net")
            ) { _, message, _, _, proxy ->
                reply = proxy
                handle(message.data ?: "")
            }
        } else {
            AlertDialog.Builder(this)
                .setMessage("Bu tabletteki Android System WebView çok eski. Play Store'dan güncelleyin.")
                .setPositiveButton("Tamam", null).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // ---------- Sayfadan gelen mesajlar ----------

    private fun handle(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        val id = msg.optInt("id", -1)
        when (msg.optString("op")) {
            "config" -> answer(id, true, JSONObject()
                .put("url", prefs.getString("url", "") ?: "")
                .put("key", prefs.getString("key", "") ?: ""))
            "settings" -> { showSettings(); answer(id, true, JSONObject.NULL) }
            "ink" -> recognizeInk(id, msg.optJSONArray("args") ?: JSONArray())
            "api" -> callApi(id, msg.optString("args"))
            "speechStart" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) startListening(id)
                else { pendingSpeechId = id; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            }
            "speechStop" -> { stopListening(); answer(id, true, JSONObject.NULL) }
            else -> answer(id, false, error = "Bilinmeyen işlem")
        }
    }

    private fun answer(id: Int, ok: Boolean, result: Any? = null, error: String? = null) {
        if (id < 0) return
        val o = JSONObject().put("id", id).put("ok", ok)
        if (ok) o.put("result", result ?: JSONObject.NULL) else o.put("error", error ?: "hata")
        main.post { reply?.postMessage(o.toString()) }
    }

    private fun event(kind: String, extra: JSONObject) {
        extra.put("type", "event").put("kind", kind)
        main.post { reply?.postMessage(extra.toString()) }
    }

    // ---------- Ayarlar: Apps Script adresi ve anahtarı ----------

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val urlField = EditText(this).apply {
            hint = "https://script.google.com/macros/s/…/exec"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("url", ""))
        }
        val keyField = EditText(this).apply {
            hint = "Uygulama anahtarı"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs.getString("key", ""))
        }
        box.addView(TextView(this).apply { text = "Apps Script web uygulaması adresi (Herkes erişimli dağıtım)" })
        box.addView(urlField)
        box.addView(TextView(this).apply { text = "Uygulama anahtarı (anahtarOlustur çıktısı)" })
        box.addView(keyField)
        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit()
                    .putString("url", urlField.text.toString().trim())
                    .putString("key", keyField.text.toString().trim())
                    .apply()
                web.reload()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- Apps Script bağlantısı (WebView yerine uygulama yapar, tarayıcı kısıtlarına takılmaz) ----------

    private fun callApi(id: Int, body: String) {
        val url = prefs.getString("url", "") ?: ""
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { post(url, body) }
                answer(id, true, text)
            } catch (e: Exception) {
                answer(id, false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun post(address: String, body: String): String {
        if (!address.startsWith("https://")) throw Exception("Adres https:// ile başlamalı")
        var conn = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 180000
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        var code = conn.responseCode
        var hops = 0
        // Apps Script cevabı başka bir adrese yönlendirerek verir; yönlendirmeyi GET ile izle
        while (code in 300..399 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            val next = URL(conn.url, loc)
            conn.disconnect()
            conn = (next.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 180000
                instanceFollowRedirects = false
            }
            code = conn.responseCode
            hops++
        }
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code: " + text.replace(Regex("<[^>]+>"), " ").trim().take(150))
        return text
    }

    // ---------- El yazısı (ML Kit) ----------

    private fun recognizeInk(id: Int, items: JSONArray) {
        lifecycleScope.launch {
            try {
                val rec = recognizer ?: run {
                    val manager = RemoteModelManager.getInstance()
                    if (!manager.isModelDownloaded(inkModel).await()) {
                        event("status", JSONObject().put("text", "Türkçe el yazısı modeli indiriliyor (yaklaşık 20 MB, bir kereye mahsus)…"))
                        manager.download(inkModel, DownloadConditions.Builder().build()).await()
                        event("status", JSONObject().put("text", "El yazısı modeli hazır"))
                    }
                    DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(inkModel).build())
                        .also { recognizer = it }
                }
                val out = JSONArray()
                for (i in 0 until items.length()) {
                    val lines = items.getJSONObject(i).optJSONArray("lines") ?: JSONArray()
                    val texts = mutableListOf<String>()
                    var before = ""
                    for (l in 0 until lines.length()) {
                        val line = lines.getJSONObject(l)
                        val ink = buildInk(line.getJSONArray("strokes")) ?: continue
                        val ctx = RecognitionContext.builder()
                            .setPreContext(before.takeLast(20))
                            .setWritingArea(WritingArea(line.optDouble("w", 0.0).toFloat(), line.optDouble("h", 0.0).toFloat()))
                            .build()
                        val result = rec.recognize(ink, ctx).await()
                        val text = result.candidates.firstOrNull()?.text ?: ""
                        if (text.isNotBlank()) { texts.add(text); before = "$before $text".trim() }
                    }
                    out.put(texts.joinToString("\n"))
                }
                answer(id, true, out)
            } catch (e: Exception) {
                answer(id, false, error = e.message ?: "El yazısı tanınamadı")
            }
        }
    }

    private fun buildInk(strokes: JSONArray): Ink? {
        val ink = Ink.builder()
        var any = false
        var clock = System.currentTimeMillis()
        for (s in 0 until strokes.length()) {
            val pts = strokes.getJSONArray(s)
            if (pts.length() == 0) continue
            val sb = Ink.Stroke.builder()
            for (p in 0 until pts.length()) {
                val q = pts.getJSONArray(p)
                var t = q.optLong(2, 0L)
                if (t <= 0L) { clock += 10; t = clock }
                sb.addPoint(Ink.Point.create(q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), t))
            }
            ink.addStroke(sb.build())
            any = true
        }
        return if (any) ink.build() else null
    }

    // ---------- Konuşma tanıma (görüşme sırasında) ----------

    private val speechIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
    }

    private fun startListening(id: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            answer(id, false, error = "Bu tablette konuşma tanıma servisi yok (Google uygulamasını kurun/güncelleyin)")
            return
        }
        if (speech == null) {
            val google = googleRecognizer()
            speech = (if (google != null) SpeechRecognizer.createSpeechRecognizer(this, google)
                      else SpeechRecognizer.createSpeechRecognizer(this)).apply { setRecognitionListener(listener) }
            event("status", JSONObject().put("text",
                if (google != null) "Ses tanıma: Google" else "Ses tanıma: tabletin varsayılan servisi (Google servisi bulunamadı)"))
        }
        listening = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speech?.startListening(speechIntent)
        event("state", JSONObject().put("state", "listening"))
        answer(id, true, JSONObject.NULL)
    }

    /** Tablette yüklü Google ses tanıma servisini bulur (Google uygulaması ya da Google Konuşma Hizmetleri). */
    private fun googleRecognizer(): ComponentName? {
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
        val order = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")
        for (pkg in order) {
            services.firstOrNull { it.packageName == pkg }?.let { return ComponentName(it.packageName, it.name) }
        }
        return services.firstOrNull { it.packageName.startsWith("com.google.") }?.let { ComponentName(it.packageName, it.name) }
    }

    private fun stopListening() {
        listening = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speech?.stopListening()
        event("state", JSONObject().put("state", "idle").put("reason", "stopped"))
    }

    private fun restart(delay: Long) {
        if (!listening) return
        main.postDelayed({ if (listening) speech?.startListening(speechIntent) }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            event("partial", JSONObject().put("text", t))
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            if (t.isNotBlank()) event("final", JSONObject().put("text", t))
            restart(150)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listening = false
                    event("state", JSONObject().put("state", "idle").put("reason", "not_granted"))
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(500)
                else -> restart(800)
            }
        }
    }

    override fun onDestroy() {
        listening = false
        speech?.destroy()
        recognizer?.close()
        super.onDestroy()
    }
}
