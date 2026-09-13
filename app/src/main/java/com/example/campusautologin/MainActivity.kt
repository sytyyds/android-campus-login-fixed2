package com.example.campusautologin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : AppCompatActivity() {
    private val ssid = "zzuli-student"
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var status: TextView
    private lateinit var connectivity: ConnectivityManager
    private var pendingAction: (() -> Unit)? = null
    private var requestedNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) pendingAction?.invoke()
        else setStatus("需要 Wi-Fi 和附近设备权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(ConnectivityManager::class.java)
        buildUi()
        loadSavedCredentials()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 40)
        }
        val title = TextView(this).apply {
            text = "校园网自动登录"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val network = TextView(this).apply {
            text = "Wi-Fi：$ssid\n运营商：中国联通"
            textSize = 16f
            setPadding(0, 28, 0, 24)
        }
        userInput = EditText(this).apply {
            hint = "学号"
            inputType = InputType.TYPE_CLASS_TEXT
            singleLine = true
        }
        passwordInput = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine = true
        }
        val login = Button(this).apply {
            text = "连接并认证"
            setOnClickListener { startLogin() }
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 28, 0, 0)
        }
        root.addView(title)
        root.addView(network)
        root.addView(userInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(passwordInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(login, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 })
        root.addView(status)
        setContentView(root)
    }

    private fun startLogin() {
        val username = userInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            setStatus("请输入学号和密码")
            return
        }
        pendingAction = { connectWifiThenLogin(username, password) }
        if (!hasWifiPermissions()) {
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
            permissionLauncher.launch(permissions.toTypedArray())
        } else pendingAction?.invoke()
    }

    private fun connectWifiThenLogin(username: String, password: String) {
        setStatus("正在检查 Wi-Fi...")
        val wifi = getSystemService(WifiManager::class.java)
        val current = wifi.connectionInfo?.ssid?.trim('"')
        if (current == ssid) {
            val network = connectivity.activeNetwork
            val caps = network?.let { connectivity.getNetworkCapabilities(it) }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                authenticate(network, username, password)
                return
            }
        }
        if (Build.VERSION.SDK_INT < 29) {
            setStatus("此 Android 版本不支持应用内连接 Wi-Fi，请先手动连接 $ssid")
            return
        }
        setStatus("请在系统弹窗中确认连接 $ssid")
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestedNetwork = network
                runOnUiThread { authenticate(network, username, password) }
            }

            override fun onUnavailable() {
                runOnUiThread { setStatus("无法连接 Wi-Fi：$ssid") }
            }
        }
        networkCallback = callback
        connectivity.requestNetwork(request, callback)
    }

    private fun authenticate(network: Network?, username: String, password: String) {
        Thread {
            try {
                val wifi = getSystemService(WifiManager::class.java)
                val ip = ipv4(wifi.connectionInfo.ipAddress)
                if (ip == "0.0.0.0") throw IllegalStateException("尚未获得 Wi-Fi IP")
                val key = ip.fold(0) { acc, c -> acc xor c.code }
                val statusBody = request(network, "http://10.168.6.10/drcom/chkstatus?callback=cbstatus")
                if (statusBody.contains("\"result\":1")) {
                    saveCredentials(username, password)
                    showResult("校园网已在线")
                    return@Thread
                }
                val account = ",0,$username@unicom"
                val params = listOf(
                    "callback" to encode("dr${(1000..9999).random()}", key),
                    "login_method" to encode("1", key),
                    "user_account" to encode(account, key),
                    "user_password" to encode(password, key),
                    "wlan_user_ip" to encode(ip, key),
                    "wlan_user_ipv6" to "",
                    "wlan_user_mac" to encode("000000000000", key),
                    "wlan_vlan_id" to encode("0", key),
                    "wlan_ac_ip" to encode("10.168.6.9", key),
                    "wlan_ac_name" to "",
                    "authex_enable" to "",
                    "jsVersion" to encode("4.2.2", key),
                    "captcha" to "",
                    "terminal_type" to encode("1", key),
                    "lang" to encode("zh-cn", key),
                    "program_index" to encode("UOgdI51730702267", key),
                    "page_index" to encode("oVPhCz1730702347", key),
                    "encrypt" to "1",
                    "v" to (500 + (0..9999).random()).toString(),
                    "lang" to "zh"
                )
                val query = params.joinToString("&") { (n, v) -> "$n=${URLEncoder.encode(v, "UTF-8")}" }
                val body = request(network, "http://10.168.6.10:801/eportal/portal/login?$query")
                val verify = request(network, "http://10.168.6.10/drcom/chkstatus?callback=cbverify")
                if (verify.contains("\"result\":1") || body.contains("在线")) {
                    saveCredentials(username, password)
                    showResult("认证成功")
                } else showResult("认证失败，请检查账号、密码或运营商")
            } catch (e: Exception) {
                showResult("连接失败：${e.message ?: "未知错误"}")
            }
        }.start()
    }

    private fun request(network: Network?, url: String): String {
        val connection = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.useCaches = false
        return connection.inputStream.use { it.readBytes().toString(Charset.forName("GB18030")) }
    }

    private fun encode(value: String, key: Int): String {
        return value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff xor key) }
    }

    private fun ipv4(value: Int): String = listOf(value and 255, value shr 8 and 255, value shr 16 and 255, value shr 24 and 255).joinToString(".")

    private fun hasWifiPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)

    private fun setStatus(message: String) = runOnUiThread { status.text = message }
    private fun showResult(message: String) = setStatus(message)

    private fun loadSavedCredentials() {
        val saved = CredentialStore(this).read()
        if (saved != null) { userInput.setText(saved.first); passwordInput.setText(saved.second) }
    }

    private fun saveCredentials(username: String, password: String) = CredentialStore(this).write(username, password)

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        super.onDestroy()
    }
}

private class CredentialStore(private val context: Context) {
    private val alias = "CampusAutoLoginKey"
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    fun write(username: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = "$username\u0000$password".toByteArray(Charsets.UTF_8)
        prefs.edit().putString("data", Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))).apply()
    }

    fun read(): Pair<String, String>? = try {
        val raw = prefs.getString("data", null) ?: return null
        val bytes = Base64.getDecoder().decode(raw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val values = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8).split('\u0000', limit = 2)
        if (values.size == 2) values[0] to values[1] else null
    } catch (_: Exception) { null }
}
