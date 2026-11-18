package ru.bashist.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()
    private var configText: String? = null
    private var isUp = false

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var importButton: Button

    private val tunnel = object : Tunnel {
        override fun getName() = "bashist"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                isUp = newState == Tunnel.State.UP
                render()
            }
        }
    }

    private val configPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        configText = it.bufferedReader().readText()
                    }
                    if (configText.isNullOrBlank()) error("Пустой файл")
                    getSharedPreferences("bashist", MODE_PRIVATE)
                        .edit().putString("wg_config", configText).apply()
                    status.text = "Конфигурация загружена. Можно подключаться."
                } catch (e: Exception) {
                    toast("Не удалось прочитать конфигурацию")
                }
            }
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) connect()
            else toast("Разрешение VPN не получено")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(this)
        configText = getSharedPreferences("bashist", MODE_PRIVATE)
            .getString("wg_config", null)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 64, 36, 36)
        }
        val title = TextView(this).apply {
            text = "БАШИСТ VPN\n🇩🇪 Германия"
            textSize = 27f
        }
        status = TextView(this).apply {
            textSize = 17f
            setPadding(0, 40, 0, 28)
        }
        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener { if (isUp) disconnect() else requestConnect() }
        }
        importButton = Button(this).apply {
            text = "Импортировать конфигурацию"
            setOnClickListener { configPicker.launch(arrayOf("*/*")) }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(toggle)
        layout.addView(importButton)
        setContentView(layout)
        render()
    }

    private fun render() {
        status.text = when {
            isUp -> "Подключено"
            configText.isNullOrBlank() -> "Не подключено\nСначала импортируй файл WireGuard (.conf)"
            else -> "Не подключено\nСервер: Германия"
        }
        toggle.text = if (isUp) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
        toggle.isEnabled = isUp || !configText.isNullOrBlank()
    }

    private fun requestConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else connect()
    }

    private fun connect() {
        val raw = configText ?: return
        executor.execute {
            try {
                val config = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                backend.setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка подключения. Проверь конфигурацию сервера.") }
            }
        }
    }

    private fun disconnect() {
        executor.execute {
            try { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            catch (e: Exception) {
                runOnUiThread { toast("Не удалось отключить VPN") }
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
