package pl.btmap.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import pl.btmap.core.LiveState
import pl.btmap.core.location.Geo
import pl.btmap.core.model.Plmn
import pl.btmap.core.radio.Operators
import pl.btmap.core.stations.StationDb
import pl.btmap.mobile.BtmapApp
import pl.btmap.mobile.ui.theme.Btmap
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private enum class MapMode(val label: String) { STATIONS("Nadajniki"), COVERAGE("Zasięg") }

/** The station the SIM in [slot] is connected to, for highlighting. */
private data class Connected(val slot: Int, val lat: Double, val lng: Double, val color: Int, val label: String)

private data class CoverageLayer(val tech: String, val band: String, val cells: Int)

/**
 * Map tab: transmitter map from the downloaded station database (with the stations the SIMs are
 * connected to highlighted and linked to the phone) or the crowdsourced coverage map from BTMap.
 */
@Composable
fun MapScreen(state: LiveState, apiBase: String, stationUrl: (pl.btmap.core.api.Station) -> String, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as BtmapApp
    val colors = Btmap.colors
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(MapMode.STATIONS) }
    val dataSim = state.snapshot.sims.firstOrNull { it.isDataSim } ?: state.snapshot.sims.firstOrNull()
    var operator by rememberSaveable { mutableStateOf(dataSim?.operator?.btmapName ?: "Plus") }
    var layerKey by rememberSaveable { mutableStateOf("") } // "LTE|1800" or "" for all bands
    var layers by remember { mutableStateOf<Map<String, List<CoverageLayer>>>(emptyMap()) }
    var version by remember { mutableStateOf("0") }
    var selected by remember { mutableStateOf<StationDb.MapSite?>(null) }
    var zoom by remember { mutableStateOf(13.0) }
    var loadedSites by remember { mutableStateOf(0) }

    val mapView = remember { createMap(context, colors.isDark) }
    val stationsOverlay = remember { StationsOverlay { site -> selected = site } }
    var coverageOverlay by remember { mutableStateOf<TilesOverlay?>(null) }

    // Coverage filter list: on opening the coverage map, send this phone's queued measurements
    // first, and reload whenever an upload lands.
    val uploads by app.core.coverageUploads.collectAsStateWithLifecycle()
    LaunchedEffect(mode) {
        if (mode == MapMode.COVERAGE && app.settings.settings.value.coverageConsent == true) {
            pl.btmap.mobile.work.CoverageUploadWorker.runNow(context)
        }
    }
    LaunchedEffect(apiBase, mode, uploads) {
        if (mode != MapMode.COVERAGE) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { fetchMeta(apiBase) }.getOrNull() }?.let { (v, l) -> version = v; layers = l }
    }

    // Station loading, debounced on map movement.
    var loadJob by remember { mutableStateOf<Job?>(null) }
    fun reloadStations() {
        loadJob?.cancel()
        loadJob = scope.launch {
            delay(250)
            zoom = mapView.zoomLevelDouble
            if (mode != MapMode.STATIONS || zoom < MIN_STATION_ZOOM) {
                stationsOverlay.sites = emptyList(); loadedSites = 0; mapView.invalidate(); return@launch
            }
            val box = mapView.boundingBox
            val sites = withContext(Dispatchers.IO) { app.core.stations.sitesIn(box.latSouth, box.lonWest, box.latNorth, box.lonEast) }
            stationsOverlay.sites = sites
            loadedSites = sites.size
            mapView.invalidate()
        }
    }

    DisposableEffect(mapView) {
        mapView.overlays.add(stationsOverlay)
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { reloadStations(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { reloadStations(); return false }
        }
        mapView.addMapListener(listener)
        mapView.onResume()
        onDispose {
            mapView.removeMapListener(listener)
            mapView.onPause()
        }
    }

    // First fix: centre on the phone once.
    var centred by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.fix != null) {
        val fix = state.fix ?: return@LaunchedEffect
        if (!centred) {
            mapView.controller.setZoom(14.0)
            mapView.controller.setCenter(GeoPoint(fix.lat, fix.lng))
            centred = true
        }
        reloadStations()
    }

    // Live data for the overlay: my position and the stations the SIMs use.
    val connected = state.snapshot.sims.mapNotNull { sim ->
        val station = sim.primary?.let(state::stationOf) ?: return@mapNotNull null
        val c = sim.operator?.let { colors.brand(Color(it.color)) } ?: colors.accent
        Connected(sim.slotIndex, station.lat, station.lng, c.toArgb(), "SIM ${sim.slotIndex + 1}")
    }
    stationsOverlay.me = state.fix?.let { GeoPoint(it.lat, it.lng) }
    stationsOverlay.connected = connected
    stationsOverlay.showStations = mode == MapMode.STATIONS
    stationsOverlay.selectedId = selected?.id
    LaunchedEffect(connected, state.fix, mode) { mapView.invalidate() }

    // Coverage tile layer for the chosen operator / band.
    LaunchedEffect(mode, operator, layerKey, version, apiBase) {
        coverageOverlay?.let { mapView.overlays.remove(it); it.onDetach(mapView) }
        coverageOverlay = null
        if (mode == MapMode.COVERAGE) {
            val (tech, band) = layerKey.split('|').let { if (it.size == 2) it[0] to it[1] else null to null }
            val source = CoverageTileSource(apiBase, operator, tech, band, version)
            val overlay = TilesOverlay(MapTileProviderBasic(context, source), context).apply {
                loadingBackgroundColor = AColor.TRANSPARENT
                loadingLineColor = AColor.TRANSPARENT
            }
            mapView.overlays.add(0, overlay)
            coverageOverlay = overlay
        }
        selected = null
        reloadStations()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = contentPadding.calculateTopPadding() + 4.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Mode switch.
            Row(
                Modifier
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.card)
                    .padding(4.dp),
            ) {
                MapMode.entries.forEach { m ->
                    val on = m == mode
                    val bg by animateColorAsState(if (on) colors.accent.copy(alpha = 0.18f) else Color.Transparent, tween(200), label = "mapMode")
                    Text(
                        m.label,
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .clickable { mode = m }
                            .padding(vertical = 10.dp),
                        color = if (on) colors.accent else colors.muted,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            AnimatedVisibility(mode == MapMode.COVERAGE, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                Column(
                    Modifier
                        .shadow(10.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.card)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Plus" to "01", "T-Mobile" to "02", "Orange" to "03", "Play" to "06").forEach { (name, mnc) ->
                            val brand = Operators.byPlmn(Plmn("260", mnc))?.let { colors.brand(Color(it.color)) } ?: colors.accent
                            ChipButton(name, operator == name, brand) { operator = name; layerKey = "" }
                        }
                    }
                    val available = layers[operator].orEmpty()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipButton("Wszystkie pasma", layerKey == "", colors.accent) { layerKey = "" }
                        available.forEach { l ->
                            val key = "${l.tech}|${l.band}"
                            ChipButton("${l.tech} ${l.band}", layerKey == key, colors.accent) { layerKey = key }
                        }
                    }
                    if (available.isEmpty()) {
                        Text(
                            "Brak jeszcze pomiarów $operator. Pojawią się, gdy użytkownicy BTMap je prześlą.",
                            style = MaterialTheme.typography.bodySmall, color = colors.muted,
                        )
                    }
                    val consent = app.settings.settings.value.coverageConsent == true
                    val pending = remember(uploads, state.snapshot.timestampMillis) { app.core.coverage.pending }
                    Text(
                        when {
                            !consent -> "Twoje pomiary nie są wysyłane — włącz w Ustawienia → Pomiary"
                            pending > 0 -> "Twoje pomiary czekające na wysłanie: $pending (wysyłka przez Wi-Fi)"
                            else -> "Twoje pomiary są wysłane"
                        },
                        style = MaterialTheme.typography.labelSmall, color = colors.faint,
                    )
                }
            }

            if (mode == MapMode.STATIONS && zoom < MIN_STATION_ZOOM) {
                Hint("Przybliż mapę, aby zobaczyć nadajniki")
            } else if (mode == MapMode.STATIONS && loadedSites == 0 && app.core.stations.info(app.core.stations.source.value) == null) {
                Hint("Pobierz bazę stacji w Ustawienia → Baza danych")
            }
        }

        // Locate me.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp)
                .size(52.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .clickable {
                    state.fix?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lng), 15.0, 600L) }
                },
            contentAlignment = Alignment.Center,
        ) { LiveIcon(colors.accent) }

        // Selected station card.
        AnimatedVisibility(
            selected != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 76.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            selected?.let { site -> SiteCard(site, state, stationUrl) { selected = null } }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Btmap.colors.card)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
    )
}

@Composable
private fun ChipButton(text: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    val colors = Btmap.colors
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) tint.copy(alpha = 0.16f) else Color.Transparent, tween(200), label = "chip")
    Row(
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, if (on) tint else colors.divider, RoundedCornerShape(10.dp))
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (on) tint else colors.faint))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onSurface else colors.muted)
    }
}

@Composable
private fun SiteCard(site: StationDb.MapSite, state: LiveState, stationUrl: (pl.btmap.core.api.Station) -> String, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = Btmap.colors
    val fix = state.fix
    val dist = fix?.let { Geo.distanceMeters(it.lat, it.lng, site.lat, site.lng) }
    val url = site.ext?.removePrefix("btsearch:")?.toLongOrNull()?.let { "https://btsearch.pl/stations/$it" }
        ?: site.ext?.let { stationUrl(pl.btmap.core.api.Station(it, site.stationId, site.name, null, site.lat, site.lng, emptyList(), emptyList(), false)) }
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(1.dp, colors.divider, RoundedCornerShape(20.dp))
            .clickable { url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            site.mncs.sorted().forEach { mnc ->
                val op = Operators.byPlmn(Plmn("260", "%02d".format(mnc)))
                Text(
                    op?.displayName ?: "260 %02d".format(mnc),
                    Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background((op?.let { colors.brand(Color(it.color)) } ?: colors.accent).copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.weight(1f))
            dist?.let { Text(formatDistance(it), style = MaterialTheme.typography.labelLarge) }
            Text("  ✕", Modifier.clickable(onClick = onClose), color = colors.muted)
        }
        Spacer(Modifier.size(6.dp))
        Text(site.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
        site.stationId?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
    }
}

// ---- osmdroid ------------------------------------------------------------------------------

private const val MIN_STATION_ZOOM = 11.0

private fun createMap(context: Context, dark: Boolean): MapView {
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = java.io.File(context.cacheDir, "osmdroid")
        osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid/tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        minZoomLevel = 5.0
        maxZoomLevel = 19.0
        controller.setZoom(13.0)
        controller.setCenter(GeoPoint(52.2297, 21.0122))
        // Dark theme: grey-scale first, then invert. Inverting colours directly turns parks and
        // forests magenta/purple; a neutral dark-grey map lets the operator colours stand out.
        if (dark) overlayManager.tilesOverlay.setColorFilter(darkMapFilter())
    }
}

private fun darkMapFilter(): android.graphics.ColorMatrixColorFilter {
    val grey = android.graphics.ColorMatrix().apply { setSaturation(0f) }
    val invert = android.graphics.ColorMatrix(
        floatArrayOf(
            -0.78f, 0f, 0f, 0f, 222f,
            0f, -0.78f, 0f, 0f, 224f,
            0f, 0f, -0.78f, 0f, 226f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    invert.preConcat(grey)
    return android.graphics.ColorMatrixColorFilter(invert)
}

/** Coverage PNG tiles from the BTMap server; the cache key includes the filter and data version. */
private class CoverageTileSource(
    private val apiBase: String,
    private val operator: String,
    private val tech: String?,
    private val band: String?,
    private val version: String,
) : OnlineTileSourceBase(
    "btmap-coverage-$operator-${tech ?: "all"}-${band ?: "all"}-$version", 5, 18, 256, ".png", arrayOf(apiBase.trimEnd('/')),
) {
    override fun getTileURLString(index: Long): String {
        val q = StringBuilder("operator=").append(URLEncoder.encode(operator, "UTF-8"))
        tech?.let { q.append("&tech=").append(URLEncoder.encode(it, "UTF-8")) }
        band?.let { q.append("&band=").append(URLEncoder.encode(it, "UTF-8")) }
        q.append("&v=").append(version)
        return "${apiBase.trimEnd('/')}/api/coverage/tiles/${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}.png?$q"
    }
}

private fun fetchMeta(apiBase: String): Pair<String, Map<String, List<CoverageLayer>>> {
    val conn = URL(apiBase.trimEnd('/') + "/api/coverage/meta").openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 15_000
    return try {
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val list = o.optJSONArray("layers")
        val byOperator = mutableMapOf<String, MutableList<CoverageLayer>>()
        if (list != null) for (i in 0 until list.length()) {
            val l = list.getJSONObject(i)
            byOperator.getOrPut(l.getString("operator")) { mutableListOf() } += CoverageLayer(l.getString("tech"), l.getString("band"), l.optInt("cells"))
        }
        o.optString("version", "0") to byOperator
    } finally {
        conn.disconnect()
    }
}

/**
 * Draws stations as operator-coloured dots (fast enough for a thousand at once), the phone, and
 * the stations the SIMs are connected to: a ring, a pulse-free halo and a dashed line from the phone.
 */
private class StationsOverlay(private val onTap: (StationDb.MapSite) -> Unit) : Overlay() {
    @Volatile var sites: List<StationDb.MapSite> = emptyList()
    @Volatile var me: GeoPoint? = null
    @Volatile var connected: List<Connected> = emptyList()
    @Volatile var showStations = true
    @Volatile var selectedId: Long? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val p = Point()
    private val q = Point()

    private fun colorOf(mnc: Int): Int = Operators.byPlmn(Plmn("260", "%02d".format(mnc)))?.color?.toInt() ?: 0xFF788992.toInt()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj: Projection = mapView.projection
        val density = mapView.resources.displayMetrics.density
        val r = 5.5f * density

        if (showStations) for (s in sites) {
            proj.toPixels(GeoPoint(s.lat, s.lng), p)
            val mncs = s.mncs.sorted()
            if (mncs.size <= 1) {
                fill.color = colorOf(mncs.firstOrNull() ?: 0)
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, fill)
            } else {
                // Shared site: one slice per operator.
                val sweep = 360f / mncs.size
                val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
                mncs.forEachIndexed { i, mnc -> fill.color = colorOf(mnc); canvas.drawArc(box, -90f + i * sweep, sweep, true, fill) }
            }
            stroke.color = AColor.WHITE; stroke.strokeWidth = 1.5f * density
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, stroke)
            if (s.id == selectedId) {
                stroke.color = AColor.WHITE; stroke.strokeWidth = 3f * density
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r + 5 * density, stroke)
            }
        }

        val mine = me
        // The coverage map shows coverage only; stations and links belong to the transmitter map.
        if (showStations) for (c in connected) {
            proj.toPixels(GeoPoint(c.lat, c.lng), p)
            if (mine != null) {
                proj.toPixels(mine, q)
                line.color = c.color
                canvas.drawLine(q.x.toFloat(), q.y.toFloat(), p.x.toFloat(), p.y.toFloat(), line)
            }
            fill.color = (c.color and 0x00FFFFFF) or 0x40000000
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 3.2f, fill)
            stroke.color = c.color; stroke.strokeWidth = 3.5f * density
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 2f, stroke)
            fill.color = c.color
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 1.2f, fill)
        }

        if (mine != null) {
            proj.toPixels(mine, p)
            fill.color = 0x332582EB
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 3f, fill)
            fill.color = 0xFF2582EB.toInt()
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 1.4f, fill)
            stroke.color = AColor.WHITE; stroke.strokeWidth = 2.5f * density
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 1.4f, stroke)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (!showStations) return false
        val proj = mapView.projection
        val maxPx = 22f * mapView.resources.displayMetrics.density
        var best: StationDb.MapSite? = null
        var bestD = Float.MAX_VALUE
        for (s in sites) {
            proj.toPixels(GeoPoint(s.lat, s.lng), p)
            val d = Math.hypot((p.x - e.x).toDouble(), (p.y - e.y).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = s }
        }
        if (best != null && bestD <= maxPx) { onTap(best); mapView.invalidate(); return true }
        return false
    }
}
