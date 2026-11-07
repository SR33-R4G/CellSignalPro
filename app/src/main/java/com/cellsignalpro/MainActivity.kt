
package com.cellsignalpro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.telephony.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class SimChoice(val id: Int, val label: String)
data class NeighborCell(val technology: String, val pci: String, val rsrp: String, val rsrq: String, val registered: Boolean)
data class RadioSnapshot(
    val technology: String = "Unknown",
    val operator: String = "Unavailable",
    val simLabel: String = "SIM 1",
    val channel: String = "—",
    val cellId: String = "—",
    val pci: String = "—",
    val tac: String = "—",
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val rssi: Int? = null,
    val level: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val band: String = "—",
    val neighbors: List<NeighborCell> = emptyList()
)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { setContent { CellSignalApp(this) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        setContent { CellSignalApp(this) }
    }
}

@Suppress("DEPRECATION")
fun simChoices(context: Context): List<SimChoice> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return listOf(SimChoice(-1, "Default SIM (permission required)"))
    }
    return runCatching {
        val subs = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val active = subs.activeSubscriptionInfoList.orEmpty()
        if (active.isEmpty()) listOf(SimChoice(-1, "Default data SIM"))
        else active.sortedBy { it.simSlotIndex }.map {
            SimChoice(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.carrierName}")
        }
    }.getOrElse { listOf(SimChoice(-1, "Default SIM")) }
}

// Android exposes some telephony values as Int and others as Long.
// Compare through Number to avoid Int/Long sentinel mismatches across SDKs.
private fun isAvailable(value: Number): Boolean =
    value.toLong() != CellInfo.UNAVAILABLE.toLong()

private fun validMetric(value: Number): Int? =
    if (isAvailable(value)) value.toInt() else null

@Suppress("DEPRECATION")
fun readRadio(context: Context, subscriptionId: Int, simLabel: String, freshCellInfo: List<CellInfo>? = null): RadioSnapshot {
    val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val tm = if (subscriptionId >= 0) runCatching { base.createForSubscriptionId(subscriptionId) }.getOrDefault(base) else base
    val operator = runCatching { tm.networkOperatorName }.getOrNull()?.ifBlank { "Unknown operator" } ?: "Unavailable"
    val type = when (tm.dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
        else -> "Unknown / unavailable"
    }
    var rsrp: Int? = null; var rsrq: Int? = null; var sinr: Int? = null; var rssi: Int? = null
    var cid = "—"; var channel = "—"; var pci = "—"; var tac = "—"; var level: Int? = null; var band = "—"
    val neighbors = mutableListOf<NeighborCell>()
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (fine || coarse) {
        runCatching {
            (freshCellInfo?.takeIf { it.isNotEmpty() } ?: tm.allCellInfo).orEmpty().forEach { cell ->
                when (cell) {
                    is CellInfoLte -> {
                        val sig = cell.cellSignalStrength
                        val id = cell.cellIdentity
                        val cellPci = if (isAvailable(id.pci)) id.pci.toString() else "—"
                        val cellRsrp = validMetric(sig.rsrp)
                        val cellRsrq = validMetric(sig.rsrq)
                        if (!cell.isRegistered) {
                            neighbors += NeighborCell("LTE", cellPci, cellRsrp?.let { "$it dBm" } ?: "—", cellRsrq?.let { "$it dB" } ?: "—", false)
                        } else {
                            rsrp = cellRsrp; rsrq = cellRsrq
                            sinr = validMetric(sig.rssnr)
                            rssi = validMetric(sig.dbm); level = sig.level
                            cid = if (isAvailable(id.ci)) id.ci.toString() else "—"
                            pci = cellPci
                            tac = if (isAvailable(id.tac)) id.tac.toString() else "—"
                            val earfcn = id.earfcn.takeIf { isAvailable(it) }
                            channel = earfcn?.let { "LTE EARFCN $it" } ?: "—"
                            band = earfcn?.let { lteBand(it) } ?: "—"
                        }
                    }
                    is CellInfoNr -> {
                        val sig = cell.cellSignalStrength as CellSignalStrengthNr
                        val id = cell.cellIdentity as CellIdentityNr
                        val cellPci = if (isAvailable(id.pci)) id.pci.toString() else "—"
                        val cellRsrp = validMetric(sig.ssRsrp)
                        val cellRsrq = validMetric(sig.ssRsrq)
                        if (!cell.isRegistered) {
                            neighbors += NeighborCell("NR", cellPci, cellRsrp?.let { "$it dBm" } ?: "—", cellRsrq?.let { "$it dB" } ?: "—", false)
                        } else {
                            rsrp = cellRsrp; rsrq = cellRsrq
                            sinr = validMetric(sig.ssSinr)
                            rssi = validMetric(sig.dbm)
                            level = sig.level
                            cid = if (isAvailable(id.nci)) id.nci.toString() else "—"
                            pci = cellPci
                            tac = if (isAvailable(id.tac)) id.tac.toString() else "—"
                            val nrarfcn = id.nrarfcn.takeIf { isAvailable(it) }
                            channel = nrarfcn?.let { "NR-ARFCN $it" } ?: "—"
                            band = nrarfcn?.let { nrBand(it) } ?: "—"
                        }
                    }
                }
            }
        }
    }
    // Some devices expose LTE RSRP/RSRQ through CellInfo but leave rssnr
    // unavailable there. Try the current SignalStrength LTE entry as a fallback.
    if (sinr == null && (type == "4G LTE" || type == "Unknown / unavailable")) {
        sinr = runCatching {
            tm.signalStrength
                ?.cellSignalStrengths
                ?.filterIsInstance<CellSignalStrengthLte>()
                ?.asSequence()
                ?.mapNotNull { validMetric(it.rssnr) }
                ?.firstOrNull()
        }.getOrNull()
    }
    var lat: Double? = null; var lon: Double? = null
    if (fine || coarse) {
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            val last = providers.mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
            lat = last?.latitude; lon = last?.longitude
        }
    }
    return RadioSnapshot(type, operator, simLabel, channel, cid, pci, tac, rsrp, rsrq, sinr, rssi, level, lat, lon, band = band, neighbors = neighbors.take(12))
}

/** Requests a fresh cell-info snapshot. Android/carriers may throttle or cache updates. */
@Suppress("DEPRECATION")
private suspend fun requestFreshCellInfo(context: Context, subscriptionId: Int): List<CellInfo>? {
    val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val tm = if (subscriptionId >= 0) {
        runCatching { base.createForSubscriptionId(subscriptionId) }.getOrDefault(base)
    } else base

    return withTimeoutOrNull(4500L) {
        suspendCancellableCoroutine { continuation ->
            try {
                tm.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(context),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (continuation.isActive) continuation.resume(cellInfo.toList())
                        }
                    }
                )
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}

// Approximate EARFCN/NR-ARFCN-to-band labels for common bands. Carrier aggregation
// and device-reported channel semantics can vary; this is an identification hint.
fun nrBand(n: Int): String = when (n) {
    in 422000..434000 -> "5G n1 (estimated)"
    in 361000..376000 -> "5G n3 (estimated)"
    in 173800..178800 -> "5G n5 (estimated)"
    in 499200..537999 -> "5G n41 (estimated)"
    in 151600..160600 -> "5G n28 (estimated)"
    in 620000..653333 -> "5G n78 (estimated)"
    in 693334..733333 -> "5G n79 (estimated)"
    else -> "NR band unknown"
}
fun lteBand(n: Int): String = when (n) {
    in 0..599 -> "LTE B1 (estimated)"
    in 1200..1949 -> "LTE B3 (estimated)"
    in 1950..2399 -> "LTE B2 (estimated)"
    in 2750..3449 -> "LTE B7 (estimated)"
    in 3450..3799 -> "LTE B8 (estimated)"
    in 6150..6449 -> "LTE B20 (estimated)"
    in 9210..9659 -> "LTE B28 (estimated)"
    else -> "LTE band unknown"
}

fun appendLog(context: Context, s: RadioSnapshot) {
    val file = java.io.File(context.filesDir, "cellsignal_log.csv")
    val header = "timestamp,sim,operator,technology,rsrp_dbm,rsrq_db,sinr_db,rssi_dbm,cell_id,pci,tac,channel,latitude,longitude\n"
    if (!file.exists()) file.writeText(header)
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(s.timestamp))
    fun csv(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    file.appendText(listOf(
        csv(time), csv(s.simLabel), csv(s.operator), csv(s.technology),
        s.rsrp?.toString() ?: "", s.rsrq?.toString() ?: "", s.sinr?.toString() ?: "",
        s.rssi?.toString() ?: "", csv(s.cellId), csv(s.pci), csv(s.tac), csv(s.channel),
        s.latitude?.toString() ?: "", s.longitude?.toString() ?: ""
    ).joinToString(",") + "\n")
}

data class FieldPoint(
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val technology: String,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val operator: String
)

private const val FIELD_POINTS_KEY = "field_points_v06"

private fun loadFieldPoints(context: Context): List<FieldPoint> = runCatching {
    val array = context.getSharedPreferences("cellsignal_map", Context.MODE_PRIVATE)
        .getString(FIELD_POINTS_KEY, "[]") ?: "[]"
    val json = JSONArray(array)
    (0 until json.length()).map { i ->
        val o = json.getJSONObject(i)
        FieldPoint(o.getLong("time"), o.getDouble("lat"), o.getDouble("lon"),
            o.optString("technology", "Unknown"),
            if (o.isNull("rsrp")) null else o.optInt("rsrp"),
            if (o.isNull("rsrq")) null else o.optInt("rsrq"),
            if (o.isNull("sinr")) null else o.optInt("sinr"),
            o.optString("operator", "Unknown"))
    }
}.getOrDefault(emptyList())

private fun saveFieldPoints(context: Context, points: List<FieldPoint>) {
    val array = JSONArray()
    points.takeLast(2000).forEach { point ->
        array.put(JSONObject().put("time", point.time).put("lat", point.latitude)
            .put("lon", point.longitude).put("technology", point.technology)
            .put("rsrp", point.rsrp ?: JSONObject.NULL).put("rsrq", point.rsrq ?: JSONObject.NULL)
            .put("sinr", point.sinr ?: JSONObject.NULL).put("operator", point.operator))
    }
    context.getSharedPreferences("cellsignal_map", Context.MODE_PRIVATE)
        .edit().putString(FIELD_POINTS_KEY, array.toString()).apply()
}

@Suppress("MissingPermission")
private suspend fun requestMapLocation(context: Context): Location? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val provider = if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        return withTimeoutOrNull(7000L) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                try {
                    manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(context)) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
    return withContext(Dispatchers.IO) {
        manager.getProviders(true).asSequence().mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}

data class SignalSample(val time: Long, val sinr: Int)

@Composable
fun CellSignalApp(context: Context) {
    var dark by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(true) }
    val sims = remember { simChoices(context) }
    val preferredSim = sims.firstOrNull()
    var selectedSim by remember { mutableIntStateOf(preferredSim?.id ?: -1) }
    var simLabel by remember { mutableStateOf(preferredSim?.label?.substringBefore(" ·") ?: "Default SIM") }
    var showSims by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(readRadio(context, selectedSim, simLabel)) }
    var history by remember { mutableStateOf(listOf<SignalSample>()) }
    var tab by remember { mutableIntStateOf(0) }
    var logEnabled by remember { mutableStateOf(true) }
    var mapRecording by remember { mutableStateOf(false) }
    var fieldPoints by remember { mutableStateOf(loadFieldPoints(context)) }
    var mapLabel by remember { mutableStateOf(context.getSharedPreferences("cellsignal_map", Context.MODE_PRIVATE).getString("survey_name", "Home signal survey") ?: "Home signal survey") }
    val bg = if (dark) Color(0xFF101827) else Color(0xFFF4F7FB)
    val panel = if (dark) Color(0xFF1E293B) else Color.White
    val fg = if (dark) Color.White else Color(0xFF172033)
    val muted = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accent = Color(0xFF38BDF8)

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) runCatching {
            val source = java.io.File(context.filesDir, "cellsignal_log.csv")
            if (source.exists()) context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
        }
    }

    val mapExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) runCatching {
            val header = "timestamp,latitude,longitude,operator,technology,rsrp_dbm,rsrq_db,sinr_db\n"
            val rows = fieldPoints.joinToString("\n") { point ->
                listOf(point.time, point.latitude, point.longitude, point.operator.replace(",", " "), point.technology,
                    point.rsrp ?: "", point.rsrq ?: "", point.sinr ?: "").joinToString(",")
            }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(header + rows) }
        }
    }

    LaunchedEffect(live, selectedSim, simLabel, logEnabled, mapRecording) {
        while (live) {
            // Ask Android for a fresh cell snapshot instead of repeatedly relying only
            // on TelephonyManager.allCellInfo, which can remain cached while moving.
            val freshInfo = requestFreshCellInfo(context, selectedSim)
            snapshot = readRadio(context, selectedSim, simLabel, freshInfo)

            // Chart sampling is independent of CSV logging.
            snapshot.sinr?.let { value ->
                history = (history + SignalSample(snapshot.timestamp, value)).takeLast(120)
            }
            if (logEnabled) appendLog(context, snapshot)
            if (mapRecording) {
                val location = requestMapLocation(context)
                if (location != null) {
                    val point = FieldPoint(
                        System.currentTimeMillis(), location.latitude, location.longitude,
                        snapshot.technology, snapshot.rsrp, snapshot.rsrq, snapshot.sinr, snapshot.operator
                    )
                    fieldPoints = (fieldPoints + point).takeLast(2000)
                    saveFieldPoints(context, fieldPoints)
                }
            }

            // Avoid aggressive polling; Android/carriers may throttle cell-info requests.
            delay(3000)
        }
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = accent, background = bg, surface = panel)
        else lightColorScheme(primary = Color(0xFF0284C7), background = bg, surface = panel)
    ) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = panel) {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Dashboard, null) }, label = { Text("Dashboard") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Engineering") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Logs") })
                    NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Map, null) }, label = { Text("Signal Map") })
                }
            }
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CellTower, null, tint = accent, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("CellSignal Pro", color = fg, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                                Text("FIELD ENGINEERING", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { dark = !dark }) {
                                Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme", tint = fg)
                            }
                            IconButton(onClick = { live = !live }) {
                                Icon(if (live) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Toggle live", tint = fg)
                            }
                        }
                    }
                }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("ACTIVE SUBSCRIPTION", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { showSims = true }) {
                                    Icon(Icons.Default.SimCard, null); Text(" $simLabel ▾")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(snapshot.technology, color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Text(snapshot.operator, color = muted, fontSize = 13.sp)
                                }
                                Text(if (live) "● LIVE" else "Ⅱ PAUSED", color = if (live) Color(0xFF22C55E) else muted)
                            }
                        }
                    }
                }

                when (tab) {
                    0 -> {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("CURRENT SINR", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(snapshot.sinr?.let { "$it dB" } ?: "— dB", color = accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                                    Text(snapshot.sinr?.let { quality(it) } ?: "SINR not currently exposed", color = muted)
                                    HorizontalDivider(color = muted.copy(alpha = .25f))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Metric("RSRP", snapshot.rsrp?.let { "$it dBm" } ?: "—", muted, fg)
                                        Metric("RSRQ", snapshot.rsrq?.let { "$it dB" } ?: "—", muted, fg)
                                        Metric("RSSI", snapshot.rssi?.let { "$it dBm" } ?: "—", muted, fg)
                                    }
                                }
                            }
                        }
                        item {
                            val values = history.map { it.sinr }
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("SESSION SINR STATISTICS", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Metric("MIN", values.minOrNull()?.let { "$it dB" } ?: "—", muted, fg)
                                        Metric("AVERAGE", values.takeIf { it.isNotEmpty() }?.let { "${it.average().toInt()} dB" } ?: "—", muted, fg)
                                        Metric("MAX", values.maxOrNull()?.let { "$it dB" } ?: "—", muted, fg)
                                    }
                                    Text("Based on ${values.size} SINR samples in this session", color = muted, fontSize = 10.sp)
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoCard("Cell ID / NCI", snapshot.cellId, panel, muted, fg, Modifier.weight(1f))
                                InfoCard("PCI", snapshot.pci, panel, muted, fg, Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoCard("TAC", snapshot.tac, panel, muted, fg, Modifier.weight(1f))
                                InfoCard("Band", snapshot.band, panel, muted, fg, Modifier.weight(1f))
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("LIVE SINR TREND", color = fg, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(10.dp))
                                    SignalChart(history, accent, muted, fg)
                                    Text("${history.size} samples · 3-second sampling", color = muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    1 -> {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("RADIO MEASUREMENTS", color = fg, fontWeight = FontWeight.Bold)
                                    Text("Serving cell · ${snapshot.technology}", color = muted, fontSize = 12.sp)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Metric("RSRP", snapshot.rsrp?.let { "$it dBm" } ?: "—", muted, fg)
                                        Metric("RSRQ", snapshot.rsrq?.let { "$it dB" } ?: "—", muted, fg)
                                        Metric("SINR", snapshot.sinr?.let { "$it dB" } ?: "—", muted, fg)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Metric("RSSI", snapshot.rssi?.let { "$it dBm" } ?: "—", muted, fg)
                                        Metric("PCI", snapshot.pci, muted, fg)
                                        Metric("TAC", snapshot.tac, muted, fg)
                                    }
                                    HorizontalDivider(color = muted.copy(alpha = .2f))
                                    InfoLine("Cell ID / NCI", snapshot.cellId, fg, muted)
                                    InfoLine("Channel", snapshot.channel, fg, muted)
                                    InfoLine("Detected band (estimated)", snapshot.band, fg, muted)
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("NEIGHBOUR CELLS", color = fg, fontWeight = FontWeight.Bold)
                                    Text("Measurements exposed by Android / modem", color = muted, fontSize = 11.sp)
                                    Spacer(Modifier.height(8.dp))
                                    if (snapshot.neighbors.isEmpty()) {
                                        Text("No neighbour-cell details currently exposed.", color = muted, fontSize = 12.sp)
                                    } else snapshot.neighbors.forEach { cell ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${cell.technology} · PCI ${cell.pci}", color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                Text(if (cell.registered) "Serving cell" else "Neighbour cell", color = muted, fontSize = 10.sp)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(cell.rsrp, color = signalColor(cell.rsrp, true), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                Text(cell.rsrq, color = signalColor(cell.rsrq, false), fontSize = 11.sp)
                                            }
                                        }
                                        HorizontalDivider(color = muted.copy(alpha = .16f))
                                    }
                                }
                            }
                        }
                        item {
                            Text("Band identification is estimated from channel ranges. Android, modem firmware and carrier policies may limit neighbour-cell and 5G NSA details.", color = muted, fontSize = 11.sp)
                        }
                    }
                    2 -> {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("SINR HISTORY", color = fg, fontWeight = FontWeight.Bold)
                                    SignalChart(history, accent, muted, fg)
                                    if (history.isNotEmpty()) {
                                        val values = history.map { it.sinr }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Metric("MIN", "${values.minOrNull()} dB", muted, fg)
                                            Metric("AVG", "${values.average().toInt()} dB", muted, fg)
                                            Metric("MAX", "${values.maxOrNull()} dB", muted, fg)
                                        }
                                    } else Text("No SINR samples recorded yet.", color = muted, fontSize = 12.sp)
                                    Text("${history.size} in-session samples · captured every 3 seconds while live monitoring is enabled", color = muted, fontSize = 11.sp)
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("FIELD LOGGING", color = fg, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = logEnabled, onCheckedChange = { logEnabled = it })
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text("Save samples to local CSV", color = fg)
                                            Text(if (logEnabled) "Logging enabled" else "Logging paused", color = muted, fontSize = 11.sp)
                                        }
                                    }
                                    Button(onClick = { exportLauncher.launch("cellsignal-field-log.csv") }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export CSV")
                                    }
                                    Text("CSV is stored in app-private storage until exported. GPS uses last-known coordinates when permission and a location fix are available.", color = muted, fontSize = 11.sp)
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("RECENT SAMPLES", color = fg, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    if (history.isEmpty()) Text("Samples will appear here when SINR is available.", color = muted, fontSize = 12.sp)
                                    else history.takeLast(20).asReversed().forEach { sample ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sample.time)), color = muted, fontSize = 12.sp)
                                            Text("${sample.sinr} dB · ${quality(sample.sinr)}", color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("LOCATION-BASED SIGNAL SURVEY", color = fg, fontWeight = FontWeight.Bold)
                                    Text("Record GPS-tagged LTE/5G readings as you walk around. Keep the app open during a survey.", color = muted, fontSize = 12.sp)
                                    OutlinedTextField(value = mapLabel, onValueChange = { mapLabel = it; context.getSharedPreferences("cellsignal_map", Context.MODE_PRIVATE).edit().putString("survey_name", it).apply() }, label = { Text("Survey name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { mapRecording = !mapRecording }, modifier = Modifier.weight(1f)) {
                                            Icon(if (mapRecording) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                            Spacer(Modifier.width(6.dp)); Text(if (mapRecording) "Stop survey" else "Start survey")
                                        }
                                        OutlinedButton(onClick = {
                                            mapRecording = false
                                            fieldPoints = emptyList()
                                            saveFieldPoints(context, fieldPoints)
                                        }) { Icon(Icons.Default.Delete, null); Text(" Clear") }
                                    }
                                    OutlinedButton(onClick = { mapExportLauncher.launch("cellsignal-location-survey.csv") }, modifier = Modifier.fillMaxWidth(), enabled = fieldPoints.isNotEmpty()) {
                                        Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export survey CSV")
                                    }
                                    Text(if (mapRecording) "● RECORDING · GPS samples requested during live monitoring (GPS may take longer)" else "Survey stopped · ${fieldPoints.size} saved points", color = if (mapRecording) Color(0xFF22C55E) else muted, fontSize = 11.sp)
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("SIGNAL COVERAGE PLOT", color = fg, fontWeight = FontWeight.Bold)
                                    FieldMapPlot(fieldPoints, muted, fg)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("● Strong", color = Color(0xFF22C55E), fontSize = 10.sp)
                                        Text("● Fair", color = Color(0xFFF59E0B), fontSize = 10.sp)
                                        Text("● Weak / no SINR", color = Color(0xFFEF4444), fontSize = 10.sp)
                                    }
                                    Text("This is a relative GPS point plot, not a street map. Accuracy depends on the phone's location fix.", color = muted, fontSize = 10.sp)
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("RECORDED LOCATIONS", color = fg, fontWeight = FontWeight.Bold)
                                    if (fieldPoints.isEmpty()) Text("No location samples yet. Start a survey outdoors or near a window to obtain a GPS fix.", color = muted, fontSize = 12.sp)
                                    else fieldPoints.takeLast(30).asReversed().forEach { point ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${point.technology} · ${point.operator}", color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                Text(String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude), color = muted, fontSize = 10.sp)
                                                Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(point.time)), color = muted, fontSize = 10.sp)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(point.sinr?.let { "$it dB SINR" } ?: "SINR —", color = point.sinr?.let { signalColor("$it dB", false) } ?: muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text(point.rsrp?.let { "$it dBm RSRP" } ?: "RSRP —", color = muted, fontSize = 10.sp)
                                            }
                                        }
                                        HorizontalDivider(color = muted.copy(alpha = .15f))
                                    }
                                }
                            }
                        }
                        item {
                            Text("Location permission is required. Android may provide approximate coordinates or delay GPS fixes; samples are stored only on this device. Background tracking is not enabled.", color = muted, fontSize = 11.sp)
                        }
                    }
                }
                item {
                    Text("Last refresh: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))}", color = muted, fontSize = 11.sp)
                }
            }
        }

        if (showSims) {
            AlertDialog(
                onDismissRequest = { showSims = false },
                title = { Text("Choose SIM") },
                text = {
                    Column {
                        sims.forEach { sim ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedSim == sim.id, onClick = {
                                    selectedSim = sim.id
                                    simLabel = sim.label.substringBefore(" ·")
                                    showSims = false
                                })
                                Text(sim.label)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSims = false }) { Text("Close") } }
            )
        }
    }
}

@Composable
fun FieldMapPlot(points: List<FieldPoint>, muted: Color, fg: Color) {
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Text("Waiting for GPS-tagged samples…", color = muted, fontSize = 12.sp)
        }
        return
    }
    val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }; val maxLon = points.maxOf { it.longitude }
    Canvas(Modifier.fillMaxWidth().height(210.dp)) {
        val pad = 18.dp.toPx()
        for (i in 0..4) {
            val x = pad + (size.width - 2 * pad) * i / 4f
            val y = pad + (size.height - 2 * pad) * i / 4f
            drawLine(muted.copy(alpha = .18f), Offset(x, pad), Offset(x, size.height - pad), 1.dp.toPx())
            drawLine(muted.copy(alpha = .18f), Offset(pad, y), Offset(size.width - pad, y), 1.dp.toPx())
        }
        val latRange = (maxLat - minLat).coerceAtLeast(0.00001)
        val lonRange = (maxLon - minLon).coerceAtLeast(0.00001)
        val coords = points.map { point ->
            Offset(pad + ((point.longitude - minLon) / lonRange).toFloat() * (size.width - 2 * pad),
                size.height - pad - ((point.latitude - minLat) / latRange).toFloat() * (size.height - 2 * pad))
        }
        coords.zipWithNext().forEach { (a, b) -> drawLine(Color(0xFF38BDF8).copy(alpha = .65f), a, b, 2.dp.toPx()) }
        coords.forEachIndexed { index, offset ->
            val sinr = points[index].sinr
            val color = when { sinr == null -> Color(0xFFEF4444); sinr >= 13 -> Color(0xFF22C55E); sinr >= 0 -> Color(0xFFF59E0B); else -> Color(0xFFEF4444) }
            drawCircle(color, if (index == coords.lastIndex) 7.dp.toPx() else 5.dp.toPx(), offset)
            if (index == coords.lastIndex) drawCircle(fg.copy(alpha = .8f), 10.dp.toPx(), offset, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${minLat.toString().take(9)}, ${minLon.toString().take(9)}", color = muted, fontSize = 9.sp)
        Text("${maxLat.toString().take(9)}, ${maxLon.toString().take(9)}", color = muted, fontSize = 9.sp)
    }
}

@Composable
fun InfoLine(label: String, value: String, fg: Color, muted: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = muted, fontSize = 12.sp)
        Text(value, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun signalColor(reading: String, isRsrp: Boolean): Color {
    val value = reading.substringBefore(" ").toIntOrNull() ?: return Color(0xFF94A3B8)
    return when {
        isRsrp && value >= -90 -> Color(0xFF22C55E)
        isRsrp && value >= -105 -> Color(0xFFF59E0B)
        isRsrp -> Color(0xFFEF4444)
        value >= -10 -> Color(0xFF22C55E)
        value >= -15 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
}

fun quality(v: Int) = when {
    v >= 20 -> "Excellent"
    v >= 13 -> "Good"
    v >= 0 -> "Fair"
    else -> "Poor"
}

@Composable
fun Metric(label: String, value: String, muted: Color, fg: Color) {
    Column {
        Text(label, color = muted, fontSize = 11.sp)
        Text(value, color = fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable
fun InfoCard(label: String, value: String, panel: Color, muted: Color, fg: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = muted, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
@Composable
fun SignalChart(values: List<SignalSample>, line: Color, muted: Color, fg: Color) {
    if (values.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("Waiting for SINR samples…", color = muted, fontSize = 12.sp)
        }
        return
    }
    val numbers = values.map { it.sinr }
    val minValue = (numbers.minOrNull() ?: 0) - 3
    val maxValue = (numbers.maxOrNull() ?: 10) + 3
    val range = (maxValue - minValue).coerceAtLeast(1)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$maxValue dB", color = muted, fontSize = 10.sp)
            Text("SINR", color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text("$minValue dB", color = muted, fontSize = 10.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            for (i in 0..3) {
                val y = size.height * i / 3
                drawLine(muted.copy(alpha = .25f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            if (values.size > 1) {
                val points = values.mapIndexed { index, sample ->
                    Offset(
                        size.width * index / (values.size - 1),
                        size.height - ((sample.sinr - minValue).toFloat() / range) * size.height
                    )
                }
                points.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, 3.dp.toPx()) }
            } else {
                val y = size.height - ((values.first().sinr - minValue).toFloat() / range) * size.height
                drawLine(line, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(values.first().time)), color = muted, fontSize = 10.sp)
            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(values.last().time)), color = muted, fontSize = 10.sp)
        }
    }
}
