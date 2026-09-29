package com.example.ktsdiag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

data class Dev(val name: String, val addr: String, val type: String, val rssi: Int, val dev: BluetoothDevice)
data class Cand(val key: String, val off: ByteArray, val on: ByteArray, val conf: String)

fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
fun ByteArray.parsed(): String {
    val a = if (all { it in 32..126 }) "\"" + String(this) + "\"" else "-"
    return "ascii=$a dec=" + joinToString(",") { (it.toInt() and 0xFF).toString() }
}
val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class Diag(val ctx: Context) {
    val adapter: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val h = Handler(Looper.getMainLooper())
    val devices = mutableStateMapOf<String, Dev>()
    var status by mutableStateOf("Disconnected")
    var sel by mutableStateOf<Dev?>(null)
    var info by mutableStateOf("")
    val log = mutableStateListOf<String>()
    val chars = mutableStateListOf<BluetoothGattCharacteristic>()
    val values = mutableStateMapOf<String, ByteArray>()
    val caps = mutableStateMapOf<String, Map<String, ByteArray>>()
    var cands by mutableStateOf<List<Cand>>(emptyList())
    var report by mutableStateOf("")
    var monitoring by mutableStateOf(false)
    var lastChange by mutableStateOf("NONE")
    var lastData by mutableStateOf("...")
    var gatt: BluetoothGatt? = null
    val ops = kotlin.collections.ArrayDeque<() -> Boolean>(); var busy = false
    fun key(c: BluetoothGattCharacteristic) = "${c.service.uuid}/${c.uuid}"

    fun addLog(ev: String, c: BluetoothGattCharacteristic? = null, v: ByteArray? = null, note: String = "") {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        log.add("$t | ${sel?.name} | ${c?.service?.uuid ?: "-"} | ${c?.uuid ?: "-"} | $ev | ${v?.hex() ?: "-"} | ${v?.parsed() ?: note}")
    }
    fun next() { while (true) { val o = ops.removeFirstOrNull(); if (o == null) { busy = false; return }; busy = true; if (o()) return } }
    fun enqueue(o: () -> Boolean) { ops.add(o); if (!busy) next() }

    fun dev(d: BluetoothDevice, rssi: Int) {
        val t = when (d.type) { BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"; BluetoothDevice.DEVICE_TYPE_LE -> "LE"; BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"; else -> "Unknown" }
        h.post { devices[d.address] = Dev(d.name ?: "(unnamed)", d.address, t, rssi, d) }
    }
    val leCb = object : ScanCallback() { override fun onScanResult(t: Int, r: ScanResult) = dev(r.device, r.rssi) }
    val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_FOUND) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                dev(d, i.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt())
            }
        }
    }
    fun scan() {
        devices.clear(); adapter.bondedDevices.forEach { dev(it, 0) }
        ctx.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_FOUND))
        adapter.startDiscovery(); adapter.bluetoothLeScanner?.startScan(leCb)
        h.postDelayed({ adapter.cancelDiscovery(); adapter.bluetoothLeScanner?.stopScan(leCb) }, 12000)
    }
    fun connect() {
        val d = sel?.dev ?: return
        status = "Connecting"; chars.clear()
        val u = d.uuids?.joinToString("\n") { "  $it" } ?: "  (none cached)"
        info = "Type: ${sel?.type}, bond=${d.bondState}\nClassic SDP UUIDs (profiles):\n$u\n" +
            "Note: Android exposes standard audio profiles (A2DP/AVRCP/HFP) only through the system. Only a vendor SPP/RFCOMM UUID above could be probed by an app; audio is not touched here."
        d.fetchUuidsWithSdp()
        gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }
    fun disconnect() { gatt?.close(); gatt = null; status = "Disconnected" }
    fun discover() { gatt?.discoverServices() }
    fun read(c: BluetoothGattCharacteristic) = enqueue { gatt?.readCharacteristic(c) == true }
    fun readAll() = chars.filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }.forEach { read(it) }
    fun notify(c: BluetoothGattCharacteristic) = enqueue {
        val g = gatt ?: return@enqueue false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return@enqueue false
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0 else { d.value = v; g.writeDescriptor(d) }
    }
    fun notifyAll() = chars.filter { it.properties and 0x30 != 0 }.forEach { notify(it) }
    fun write(c: BluetoothGattCharacteristic, b: ByteArray) {
        val g = gatt ?: return
        addLog("WRITE", c, b)
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, b, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        else { c.value = b; g.writeCharacteristic(c) }
    }
    fun record(c: BluetoothGattCharacteristic, v: ByteArray, ev: String) {
        h.post {
            val k = key(c); val old = values[k]
            if (monitoring && old != null && !old.contentEquals(v)) lastChange = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ${c.uuid}: ${old.hex()} -> ${v.hex()}"
            values[k] = v; lastData = "${c.uuid}: ${v.hex()}"; addLog(ev, c, v)
        }
    }
    val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            h.post {
                if (ns == BluetoothProfile.STATE_CONNECTED) { status = "Connected"; addLog("CONNECTED") }
                else { status = if (st != 0) "Connection error (GATT status $st) - device may not expose BLE/GATT" else "Disconnected"; addLog("DISCONNECTED", note = "status=$st"); g.close() }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            h.post {
                chars.clear(); g.services.forEach { chars.addAll(it.characteristics) }
                info += "\nBLE/GATT: ${g.services.size} services, ${chars.size} characteristics"; addLog("SERVICES", note = "${g.services.size} services")
            }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, s: Int) { record(c, v, "READ"); h.post { next() } }
        @Deprecated("old") override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) { c.value?.let { record(c, it, "READ") }; h.post { next() } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) { record(c, v, "NOTIFY") }
        @Deprecated("old") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { c.value?.let { record(c, it, "NOTIFY") } }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) { h.post { addLog("NOTIFY_ENABLE", d.characteristic, note = "status=$s"); next() } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) { h.post { addLog("WRITE_RESULT", c, note = "status=$s") } }
    }
    fun capture(label: String) {
        readAll(); notifyAll()
        h.postDelayed({ caps[label] = HashMap(values); addLog("CAPTURE", note = "$label: ${values.size} values") }, 2500)
    }
    fun compare() {
        val a = caps["OFF1"]; val b = caps["ON"]; val c = caps["OFF2"]
        if (a == null || b == null) { report = "Capture OFF1 and ON first (OFF2 recommended)."; return }
        val out = b.keys.mapNotNull { k ->
            val off = a[k] ?: return@mapNotNull null; val on = b[k]!!
            if (off.contentEquals(on)) return@mapNotNull null
            val off2 = c?.get(k)
            Cand(k, off, on, if (off2 != null && off2.contentEquals(off)) "POSSIBLE (OFF->ON->OFF consistent)" else "WEAK (changed, not reproduced or no OFF2)")
        }
        cands = out
        report = if (out.isEmpty()) "No Bluetooth-level flashlight state signal was detected.\n\nThe speaker does not appear to expose its flashlight state through the Bluetooth interface accessible to this Android app. Camera/light-sensor detection may be required as an alternative."
        else "Potentially related characteristics (CANDIDATES ONLY - not verified):\n\n" + out.mapIndexed { i, x -> "${i + 1}. ${x.key}\nOFF: ${x.off.hex()}\nON: ${x.on.hex()}\nConfidence: ${x.conf}" }.joinToString("\n\n")
    }
    fun flash(): String {
        val x = cands.firstOrNull { it.conf.startsWith("POSSIBLE") } ?: return "UNKNOWN"
        val v = values[x.key] ?: return "UNKNOWN"
        return if (v.contentEquals(x.on)) "ON (candidate signal, unverified)" else if (v.contentEquals(x.off)) "OFF (candidate signal, unverified)" else "UNKNOWN"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val d = Diag(this)
        setContent { MaterialTheme { App(d) } }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun App(d: Diag) {
    var screen by remember { mutableStateOf("main") }
    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { g -> g }) d.scan() else d.status = "Permissions denied" }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("KTS-1706 Bluetooth Diagnostic", style = MaterialTheme.typography.titleLarge)
        if (screen != "main") TextButton({ screen = "main" }) { Text("< Back") }
        Text("Device: ${d.sel?.let { it.name + " " + it.addr } ?: "-"}   Status: ${d.status}")
        when (screen) {
            "main" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ ask.launch(perms) }) { Text("SCAN") }
                    Button({ d.connect() }, enabled = d.sel != null) { Text("CONNECT") }
                    OutlinedButton({ d.disconnect() }) { Text("DISCONNECT") }
                }
                if (d.status == "Connected") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ d.discover(); screen = "svc" }) { Text("SERVICES") }
                    Button({ screen = "flash" }) { Text("FLASHLIGHT TEST") }
                    Button({ screen = "log" }) { Text("LOG") }
                }
                if (d.info.isNotEmpty()) Text(d.info, style = MaterialTheme.typography.bodySmall)
                LazyColumn { items(d.devices.values.sortedWith(compareByDescending<Dev> { it.name.contains("1706", true) || it.name.contains("KBroad", true) }.thenByDescending { it.rssi }).toList()) { v ->
                    Card(onClick = { d.sel = v }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${v.name}\n${v.addr} | ${v.type} | RSSI ${v.rssi} | bond ${v.dev.bondState}", Modifier.padding(8.dp))
                    }
                } }
            }
            "svc" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ d.readAll() }) { Text("READ ALL") }; Button({ d.notifyAll() }) { Text("NOTIFY ALL") } }
                LazyColumn { items(d.chars.toList()) { c -> CharRow(d, c) } }
            }
            "flash" -> {
                var mon by remember { mutableStateOf(false) }
                Text("Bluetooth connection: ${if (d.status == "Connected") "CONNECTED" else "DISCONNECTED"}")
                Text("Flashlight status: ${d.flash()}")
                Text("Last detected change: ${d.lastChange}")
                Text("Last received Bluetooth data: ${d.lastData}")
                Button({ mon = !mon; d.monitoring = mon; if (mon) { d.readAll(); d.notifyAll() } }) { Text(if (mon) "STOP MONITORING" else "START MONITORING") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text("Manual test (captures: ${d.caps.keys.joinToString()})")
                        Text("1. Turn flashlight OFF"); Button({ d.capture("OFF1") }) { Text("CAPTURE STATE (OFF)") }
                        Text("2. Turn flashlight ON"); Button({ d.capture("ON") }) { Text("CAPTURE STATE (ON)") }
                        Text("3. Turn flashlight OFF again"); Button({ d.capture("OFF2") }) { Text("CAPTURE STATE (OFF again)") }
                        Button({ d.compare() }) { Text("COMPARE FLASHLIGHT STATES") }
                        Text(d.report)
                    }
                }
            }
            "log" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ d.log.clear() }) { Text("CLEAR LOG") }
                    Button({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "time | device | service | char | event | raw | parsed\n" + d.log.joinToString("\n")), "Export log")) }) { Text("EXPORT LOG") }
                }
                LazyColumn { items(d.log.toList()) { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}

@Composable
fun CharRow(d: Diag, c: BluetoothGattCharacteristic) {
    val p = c.properties; var hexIn by remember { mutableStateOf("") }; var warn by remember { mutableStateOf(false) }
    val v = d.values[d.key(c)]
    val flags = listOfNotNull(if (p and 2 != 0) "R" else null, if (p and 12 != 0) "W" else null, if (p and 16 != 0) "NOTIFY" else null, if (p and 32 != 0) "INDICATE" else null).joinToString(",")
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Column(Modifier.padding(8.dp)) {
        Text("Svc ${c.service.uuid}\nChr ${c.uuid} [$flags]", style = MaterialTheme.typography.bodySmall)
        if (v != null) Text("HEX ${v.hex()}\n${v.parsed()}")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p and 2 != 0) Button({ d.read(c) }) { Text("READ") }
            if (p and 0x30 != 0) Button({ d.notify(c) }) { Text("NOTIFY") }
        }
        if (p and 12 != 0) {
            OutlinedTextField(hexIn, { hexIn = it }, label = { Text("HEX e.g. AA BB 01") }, modifier = Modifier.fillMaxWidth())
            Button({ warn = true }) { Text("WRITE/TEST") }
        }
    } }
    if (warn) AlertDialog(onDismissRequest = { warn = false },
        title = { Text("Send unknown command?") },
        text = { Text("An unknown command could cause unexpected behavior on the speaker. Sends once only.") },
        confirmButton = { TextButton({ warn = false; runCatching { d.write(c, hexIn.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()) } }) { Text("SEND") } },
        dismissButton = { TextButton({ warn = false }) { Text("CANCEL") } })
}
