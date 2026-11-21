package fr.telecommande.lg

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.app.Activity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#14171c"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(web), "AndroidWS")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** Pont WebSocket natif : accepte le certificat auto-signé de la TV (réseau local uniquement). */
    class Bridge(private val web: WebView) {

        private val socks = ConcurrentHashMap<Int, WebSocket>()

        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private val client: OkHttpClient = run {
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        private fun isPrivate(host: String): Boolean {
            val m = Regex("^(\\d+)\\.(\\d+)\\.\\d+\\.\\d+$").find(host) ?: return false
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
        }

        private fun js(id: Int, ev: String, data: String, code: Int) {
            val arg = JSONObject.quote(data)
            web.post { web.evaluateJavascript("window.__ws($id,'$ev',$arg,$code)", null) }
        }

        @JavascriptInterface
        fun open(id: Int, url: String) {
            val host = try { URI(url).host ?: "" } catch (e: Exception) { "" }
            if (!isPrivate(host)) { js(id, "close", "", 1006); return }
            val req = try { Request.Builder().url(url).build() } catch (e: Exception) { js(id, "close", "", 1006); return }
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = js(id, "open", "", 0)
                override fun onMessage(webSocket: WebSocket, text: String) = js(id, "message", text, 0)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socks.remove(id); js(id, "close", "", code)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socks.remove(id); js(id, "close", t.message ?: "", 1006)
                }
            })
            socks[id] = ws
        }

        @JavascriptInterface
        fun send(id: Int, data: String) { socks[id]?.send(data) }

        @JavascriptInterface
        fun close(id: Int) { socks.remove(id)?.close(1000, null) }
    }
}
