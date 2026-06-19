package com.ps2pad.gamepad

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var net: NetworkClient
    private lateinit var usb: UsbClient
    private lateinit var gamepad: GamepadView
    private lateinit var status: TextView
    private lateinit var ipField: EditText
    private lateinit var bar: View
    private lateinit var playerBtn: Button
    private lateinit var usbBtn: Button

    // when true, input goes to the PC over the USB cable (TCP) instead of Wi-Fi.
    @Volatile private var usbMode = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        net = NetworkClient(this)
        usb = UsbClient()
        gamepad = findViewById(R.id.gamepad)
        status = findViewById(R.id.status)
        ipField = findViewById(R.id.ipField)
        bar = findViewById(R.id.bar)
        playerBtn = findViewById(R.id.playerBtn)
        usbBtn = findViewById(R.id.usbBtn)

        // route each packet to whichever transport is active (USB cable or Wi-Fi)
        gamepad.onInput = { packet -> if (usbMode) usb.send(packet) else net.send(packet) }
        usbBtn.setOnClickListener { toggleUsb() }

        // which virtual controller this phone drives; restored from prefs.
        // Tap to cycle P1→P2→P3→P4 so a second phone can be set to P2, etc.
        gamepad.state.player = prefs().getInt("player", 0).coerceIn(0, MAX_PLAYER)
        updatePlayerBtn()
        playerBtn.setOnClickListener {
            gamepad.state.player = (gamepad.state.player + 1) % (MAX_PLAYER + 1)
            prefs().edit().putInt("player", gamepad.state.player).apply()
            updatePlayerBtn()
            toast("This phone is now Player ${gamepad.state.player + 1}")
        }

        findViewById<Button>(R.id.connectBtn).setOnClickListener { connect() }
        findViewById<Button>(R.id.discoverBtn).setOnClickListener { discover() }
        findViewById<Button>(R.id.hideBtn).setOnClickListener {
            bar.visibility = View.GONE
            toast("Tap the top-left corner to show the bar again")
        }
        // tapping the very top-left corner brings the bar back
        gamepad.onMenu = { bar.visibility = View.VISIBLE }

        val editBtn = findViewById<Button>(R.id.editBtn)
        editBtn.setOnClickListener {
            gamepad.editMode = !gamepad.editMode
            editBtn.text = if (gamepad.editMode) "Done" else "Edit"
            if (gamepad.editMode) {
                bar.visibility = View.VISIBLE
                toast("Drag any control to reposition it. Tap Done when finished.")
            } else {
                toast("Layout saved")
            }
        }
        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            gamepad.resetLayout()
            toast("Layout reset to default")
        }

        // restore last IP
        prefs().getString("pc_ip", null)?.let { ipField.setText(it) }
    }

    private fun connect() {
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) { toast("Enter the PC IP or tap Discover"); return }
        net.setTarget(ip)
        prefs().edit().putString("pc_ip", ip).apply()
        setStatus("connected → $ip", true)
        toast("Sending input to $ip:9999")
    }

    private fun discover() {
        setStatus("searching…", false)
        Thread {
            val ip = net.discover()
            runOnUiThread {
                if (ip != null) {
                    ipField.setText(ip)
                    net.setTarget(ip)
                    prefs().edit().putString("pc_ip", ip).apply()
                    setStatus("found $ip", true)
                    toast("Found PC at $ip")
                } else {
                    setStatus("not found — type IP manually", false)
                    toast("No PC found. Type the IP shown by the server.")
                }
            }
        }.start()
    }

    /** Flip between USB (wired) and Wi-Fi transports. */
    private fun toggleUsb() {
        usbMode = !usbMode
        if (usbMode) {
            usb.enable()
            usbBtn.text = "USB ✓"
            setStatus("USB… connecting", false)
            toast("USB mode. If your phone asks, allow USB debugging.")
            pollUsbStatus()
        } else {
            usb.disable()
            usbBtn.text = "USB"
            ui.removeCallbacks(usbStatusPoll)
            val ip = net.targetIp()
            if (ip != null) setStatus("connected → $ip", true)
            else setStatus("offline", false)
        }
    }

    // While in USB mode, reflect the live cable-link state in the status text.
    private val usbStatusPoll = object : Runnable {
        override fun run() {
            if (!usbMode) return
            if (usb.connected) setStatus("USB connected", true)
            else setStatus("USB… waiting for cable", false)
            ui.postDelayed(this, 500)
        }
    }

    private fun pollUsbStatus() {
        ui.removeCallbacks(usbStatusPoll)
        ui.post(usbStatusPoll)
    }

    private fun updatePlayerBtn() {
        playerBtn.text = "P${gamepad.state.player + 1}"
    }

    private fun setStatus(msg: String, ok: Boolean) {
        status.text = msg
        status.setTextColor(if (ok) 0xFF4CD964.toInt() else 0xFFFF6B6B.toInt())
    }

    private fun prefs() = getSharedPreferences("ps2pad", Context.MODE_PRIVATE)

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(usbStatusPoll)
        net.close()
        usb.close()
    }

    companion object {
        // highest player index the app will cycle to (P1..P4); must stay within
        // MAX_PLAYERS in pc-server/server.py.
        private const val MAX_PLAYER = 3
    }
}
