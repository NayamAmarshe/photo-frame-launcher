package com.family.photoframe

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Pair with, connect to and disconnect from Bluetooth speakers, from inside the frame app.
 * Once a speaker is connected, Android sends all sound (music apps, videos) to it by itself.
 */
class BtController(private val ctx: Context) {

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val pendingConnect: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var a2dp: BluetoothProfile? = null
    private var registered = false
    @Volatile private var scanning = false
    @Volatile private var lastMessage = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    synchronized(found) { found[d.address] = d }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_BONDED && pendingConnect.remove(d.address)) connectAudio(d)
                    if (state == BluetoothDevice.BOND_NONE) {
                        if (pendingConnect.remove(d.address)) lastMessage = "Pairing did not finish. Put the speaker in pairing mode and try again."
                    }
                }
            }
        }
    }

    fun start() {
        val a = adapter ?: return
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ctx.registerReceiver(receiver, f)
            registered = true
        }
        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
    }

    fun stop() {
        try { adapter?.cancelDiscovery() } catch (e: Exception) { }
        if (registered) {
            try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { }
            registered = false
        }
        a2dp?.let { try { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } catch (e: Exception) { } }
        a2dp = null
    }

    private fun isSpeaker(d: BluetoothDevice): Boolean {
        val major = d.bluetoothClass?.majorDeviceClass
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    fun status(): String {
        val a = adapter ?: return JSONObject().put("supported", false).toString()
        val arr = JSONArray()
        if (a.isEnabled) {
            val connected = try { a2dp?.connectedDevices?.map { it.address }?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
            val all = LinkedHashMap<String, BluetoothDevice>()
            try { a.bondedDevices?.forEach { all[it.address] = it } } catch (e: Exception) { }
            synchronized(found) { found.forEach { (k, v) -> if (!all.containsKey(k)) all[k] = v } }
            for ((addr, d) in all) {
                val name = try { d.name } catch (e: Exception) { null }
                arr.put(
                    JSONObject()
                        .put("address", addr)
                        .put("name", if (name.isNullOrBlank()) addr else name)
                        .put("bonded", d.bondState == BluetoothDevice.BOND_BONDED)
                        .put("bonding", d.bondState == BluetoothDevice.BOND_BONDING || pendingConnect.contains(addr))
                        .put("connected", connected.contains(addr))
                        .put("speaker", isSpeaker(d))
                )
            }
        }
        return JSONObject()
            .put("supported", true)
            .put("enabled", a.isEnabled)
            .put("scanning", scanning)
            .put("message", lastMessage)
            .put("devices", arr)
            .toString()
    }

    fun setEnabled(on: Boolean) {
        val a = adapter ?: return
        try { if (on) a.enable() else a.disable() } catch (e: Exception) { }
    }

    fun scan() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        lastMessage = ""
        synchronized(found) { found.clear() }
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: SecurityException) {
            lastMessage = "Android needs the location permission to search. Allow it in the Wi-Fi screen first."
        }
    }

    /** Tap a speaker: pair if needed, then connect its audio. */
    fun connect(address: String): String {
        val a = adapter ?: return "error:No Bluetooth on this photo frame"
        lastMessage = ""
        val d = try { a.getRemoteDevice(address) } catch (e: Exception) { return "error:Unknown device" }
        try { if (a.isDiscovering) a.cancelDiscovery() } catch (e: Exception) { }
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            pendingConnect.add(address)
            return if (d.createBond()) "pairing" else "error:Could not start pairing"
        }
        return connectAudio(d)
    }

    private fun connectAudio(d: BluetoothDevice): String {
        val p = a2dp ?: return "error:Bluetooth audio is not ready yet"
        // Disconnect whatever else is playing first: a tablet can send music to one speaker at a time.
        try {
            p.connectedDevices?.filter { it.address != d.address }?.forEach { callHidden(p, "disconnect", it) }
        } catch (e: Exception) { }
        return if (callHidden(p, "connect", d)) "ok" else "error:Android would not connect to this speaker"
    }

    fun disconnect(address: String): String {
        val p = a2dp ?: return "error:Bluetooth audio is not ready yet"
        val d = try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null } ?: return "error:Unknown device"
        return if (callHidden(p, "disconnect", d)) "ok" else "error:Could not disconnect"
    }

    /** Forget a paired speaker. */
    fun forget(address: String): String {
        val d = try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null } ?: return "error:Unknown device"
        return try {
            d.javaClass.getMethod("removeBond").invoke(d)
            "ok"
        } catch (e: Exception) {
            "error:Could not forget this device"
        }
    }

    /** connect() / disconnect() exist on the audio profile but are not in Android's public list. */
    private fun callHidden(p: BluetoothProfile, method: String, d: BluetoothDevice): Boolean = try {
        val m = p.javaClass.getMethod(method, BluetoothDevice::class.java)
        (m.invoke(p, d) as? Boolean) ?: true
    } catch (e: Exception) {
        false
    }
}
