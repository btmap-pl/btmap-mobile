package pl.btmap.core.stations

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import pl.btmap.core.model.Rat

/** Where a downloaded station database comes from. */
enum class StationSource(val id: Int, val label: String) {
    /** btmapv2 stations added by the community — no UKE registry records. */
    BTMAP(1, "BTMap"),
    /** btsearch.pl export (NetMonster format). */
    BTSEARCH(2, "BTSearch"),
}

data class SourceInfo(val updatedAtMillis: Long, val sites: Int, val cells: Int, val detail: String?)

/** A site with the cells of one node, as stored. */
data class DbSite(
    val stationId: String?,
    val name: String,
    val lat: Double,
    val lng: Double,
    val channels: List<Int>,
    val externalId: String?,
    /** Band labels stored with the site (BTMap permits); BTSearch sites derive them from channels. */
    val bands: List<String>,
)

/**
 * On-device cell database. Sites are stored once and cells reference them, which keeps a
 * nationwide import (hundreds of thousands of cells) small. WAL mode lets lookups run while an
 * import rewrites a source inside one transaction, so a failed download never leaves a half table.
 */
class StationDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "stations.db", null, 4) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sites(id INTEGER PRIMARY KEY, src INTEGER NOT NULL, station TEXT, name TEXT NOT NULL,
               lat REAL NOT NULL, lng REAL NOT NULL, ext TEXT, bands TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE cells(src INTEGER NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, rat INTEGER NOT NULL,
               node INTEGER, cid INTEGER, area INTEGER, pci INTEGER, ch INTEGER, site INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX cells_node ON cells(src, mcc, mnc, rat, node)")
        db.execSQL("CREATE INDEX sites_src ON sites(src)")
        db.execSQL(PHYS_INDEX)
        db.execSQL(OP_META)
        MAP_INDEXES.forEach(db::execSQL)
        db.execSQL("CREATE TABLE meta(src INTEGER PRIMARY KEY, updated INTEGER, sites INTEGER, cells INTEGER, detail TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(PHYS_INDEX)
        if (oldVersion < 3) db.execSQL(OP_META)
        if (oldVersion < 4) MAP_INDEXES.forEach(db::execSQL)
    }

    /** A cell matched by channel + PCI, with its identity and site. */
    data class PhysicalMatch(val site: DbSite, val node: Long?, val cid: Long?, val area: Int?)

    /**
     * Cell on [channel] with [pci] nearest to the phone (PCIs repeat across the country), within
     * roughly [maxKm]. Used to name neighbours, which the modem reports without a cell id.
     */
    fun findByPhysical(
        source: StationSource, mcc: Int, mnc: Int, rat: Rat, channel: Int, pci: Int,
        lat: Double, lng: Double, maxKm: Double = 30.0,
    ): PhysicalMatch? {
        val dLat = maxKm / 111.0
        val dLng = maxKm / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val cos2 = Math.cos(Math.toRadians(lat)).let { it * it }
        val sql = """SELECT s.station, s.name, s.lat, s.lng, s.ext, s.bands, c.node, c.cid, c.area
            FROM cells c JOIN sites s ON s.id = c.site
            WHERE c.src = ? AND c.mcc = ? AND c.mnc = ? AND c.rat = ? AND c.ch = ? AND c.pci = ?
              AND s.lat BETWEEN ? AND ? AND s.lng BETWEEN ? AND ?
            ORDER BY (s.lat - ?) * (s.lat - ?) + (s.lng - ?) * (s.lng - ?) * ? LIMIT 1"""
        val args = arrayOf(
            source.id.toString(), mcc.toString(), mnc.toString(), rat.ordinal.toString(), channel.toString(), pci.toString(),
            (lat - dLat).toString(), (lat + dLat).toString(), (lng - dLng).toString(), (lng + dLng).toString(),
            lat.toString(), lat.toString(), lng.toString(), lng.toString(), cos2.toString(),
        )
        return readableDatabase.rawQuery(sql, args).use {
            if (!it.moveToFirst()) return null
            PhysicalMatch(
                site = DbSite(
                    stationId = it.getString(0), name = it.getString(1), lat = it.getDouble(2), lng = it.getDouble(3),
                    externalId = it.getString(4), channels = emptyList(),
                    bands = it.getString(5)?.split(',')?.filter(String::isNotBlank).orEmpty(),
                ),
                node = if (it.isNull(6)) null else it.getLong(6),
                cid = if (it.isNull(7)) null else it.getLong(7),
                area = if (it.isNull(8)) null else it.getInt(8),
            )
        }
    }

    private companion object {
        const val PHYS_INDEX = "CREATE INDEX IF NOT EXISTS cells_phys ON cells(src, mcc, mnc, rat, ch, pci)"
        /** Map queries: sites by position, and their cells. */
        val MAP_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS sites_pos ON sites(src, lat, lng)",
            "CREATE INDEX IF NOT EXISTS cells_site ON cells(site)",
        )
        /** When each operator of a source was last downloaded, so fresh ones are not fetched again. */
        const val OP_META = "CREATE TABLE IF NOT EXISTS op_meta(src INTEGER NOT NULL, mnc INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(src, mnc))"
    }

    fun info(source: StationSource): SourceInfo? =
        readableDatabase.rawQuery("SELECT updated, sites, cells, detail FROM meta WHERE src = ?", arrayOf(source.id.toString())).use {
            if (it.moveToFirst()) SourceInfo(it.getLong(0), it.getInt(1), it.getInt(2), it.getString(3)) else null
        }

    /** Site serving [node] of the given network, with every channel seen on it. */
    /**
     * Site of a 2G/3G cell: by RNC + cell id (UMTS) or cell id alone (GSM), preferring the row whose
     * LAC matches, since short cell ids repeat across the country.
     */
    fun findCell(source: StationSource, mcc: Int, mnc: Int, rat: Rat, node: Long?, cid: Long, area: Int?): DbSite? {
        val sql = """SELECT s.station, s.name, s.lat, s.lng, s.ext, GROUP_CONCAT(DISTINCT c.ch), s.bands, MAX(c.area = ?)
            FROM cells c JOIN sites s ON s.id = c.site
            WHERE c.src = ? AND c.mcc = ? AND c.mnc = ? AND c.rat = ? AND c.cid = ?
              AND (? = -1 OR c.node = ?)
            GROUP BY c.site ORDER BY MAX(c.area = ?) DESC, COUNT(*) DESC LIMIT 2"""
        val args = arrayOf(
            (area ?: -1).toString(),
            source.id.toString(), mcc.toString(), mnc.toString(), rat.ordinal.toString(), cid.toString(),
            (node ?: -1).toString(), (node ?: -1).toString(), (area ?: -1).toString(),
        )
        return readableDatabase.rawQuery(sql, args).use {
            if (!it.moveToFirst()) return null
            // Several sites share this id and none has the phone's LAC: ambiguous, no guess.
            if (it.count > 1 && it.getInt(7) != 1) return null
            DbSite(
                stationId = it.getString(0), name = it.getString(1), lat = it.getDouble(2), lng = it.getDouble(3),
                externalId = it.getString(4),
                channels = it.getString(5)?.split(',')?.mapNotNull(String::toIntOrNull).orEmpty(),
                bands = it.getString(6)?.split(',')?.filter(String::isNotBlank).orEmpty(),
            )
        }
    }

    fun findNode(source: StationSource, mcc: Int, mnc: Int, rat: Rat, node: Long): DbSite? {
        val db = readableDatabase
        val sql = """SELECT s.station, s.name, s.lat, s.lng, s.ext, GROUP_CONCAT(DISTINCT c.ch), s.bands
            FROM cells c JOIN sites s ON s.id = c.site
            WHERE c.src = ? AND c.mcc = ? AND c.mnc = ? AND c.rat = ? AND c.node = ?
            GROUP BY c.site ORDER BY COUNT(*) DESC LIMIT 1"""
        return db.rawQuery(sql, arrayOf(source.id.toString(), mcc.toString(), mnc.toString(), rat.ordinal.toString(), node.toString())).use {
            if (!it.moveToFirst()) return null
            DbSite(
                stationId = it.getString(0),
                name = it.getString(1),
                lat = it.getDouble(2),
                lng = it.getDouble(3),
                externalId = it.getString(4),
                channels = it.getString(5)?.split(',')?.mapNotNull(String::toIntOrNull).orEmpty(),
                bands = it.getString(6)?.split(',')?.filter(String::isNotBlank).orEmpty(),
            )
        }
    }

    /**
     * Replaces everything of [source] with what [fill] writes, atomically. On an exception the
     * previous data stays untouched.
     */
    fun replace(source: StationSource, detail: String?, fill: (Writer) -> Unit): SourceInfo {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val src = source.id.toString()
            db.delete("cells", "src = ?", arrayOf(src))
            db.delete("sites", "src = ?", arrayOf(src))
            val writer = Writer(db, source)
            fill(writer)
            val info = SourceInfo(System.currentTimeMillis(), writer.sites, writer.cells, detail)
            db.insertWithOnConflict(
                "meta", null,
                ContentValues().apply {
                    put("src", source.id); put("updated", info.updatedAtMillis)
                    put("sites", info.sites); put("cells", info.cells); put("detail", detail)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            return info
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Replaces the cells of one operator ([mnc]) of [source], atomically, so each operator becomes
     * usable as soon as it is downloaded. [detail] is stored as the source's description (the
     * operators present).
     */
    fun replaceOperator(source: StationSource, mnc: Int, detail: String?, fill: (Writer) -> Unit): SourceInfo {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val src = source.id.toString()
            db.delete("cells", "src = ? AND mnc = ?", arrayOf(src, mnc.toString()))
            val writer = Writer(db, source)
            fill(writer)
            if (writer.cells > 0) {
                db.insertWithOnConflict(
                    "op_meta", null,
                    ContentValues().apply { put("src", source.id); put("mnc", mnc); put("updated", System.currentTimeMillis()) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            } else {
                db.delete("op_meta", "src = ? AND mnc = ?", arrayOf(src, mnc.toString()))
            }
            // Sites left without cells belonged only to the replaced operator's old data.
            db.execSQL("DELETE FROM sites WHERE src = ? AND NOT EXISTS (SELECT 1 FROM cells c WHERE c.site = sites.id)", arrayOf(src))
            val sites = db.rawQuery("SELECT COUNT(*) FROM sites WHERE src = ?", arrayOf(src)).use { it.moveToFirst(); it.getInt(0) }
            val cells = db.rawQuery("SELECT COUNT(*) FROM cells WHERE src = ?", arrayOf(src)).use { it.moveToFirst(); it.getInt(0) }
            val info = SourceInfo(System.currentTimeMillis(), sites, cells, detail)
            db.insertWithOnConflict(
                "meta", null,
                ContentValues().apply {
                    put("src", source.id); put("updated", info.updatedAtMillis)
                    put("sites", sites); put("cells", cells); put("detail", detail)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            return info
        } finally {
            db.endTransaction()
        }
    }

    /** A site on the map: position, name and the networks (MNC) that use it. */
    data class MapSite(val id: Long, val lat: Double, val lng: Double, val name: String, val stationId: String?, val mncs: Set<Int>, val ext: String?)

    /** Sites of [source] inside a box, at most [limit]; for the map. */
    fun sitesIn(source: StationSource, south: Double, west: Double, north: Double, east: Double, limit: Int = 1500): List<MapSite> {
        val sql = """SELECT s.id, s.lat, s.lng, s.name, s.station, s.ext, GROUP_CONCAT(DISTINCT c.mnc)
            FROM sites s JOIN cells c ON c.site = s.id
            WHERE s.src = ? AND s.lat BETWEEN ? AND ? AND s.lng BETWEEN ? AND ?
            GROUP BY s.id LIMIT ?"""
        val args = arrayOf(source.id.toString(), south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
        return readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    MapSite(
                        c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3), c.getString(4),
                        c.getString(6)?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet().orEmpty(), c.getString(5),
                    ),
                )
            }
        }
    }

    /** Last download time per operator (MNC) of [source]; older data falls back to the source's time. */
    fun operatorUpdated(source: StationSource): Map<Int, Long> {
        val src = source.id.toString()
        val own = readableDatabase.rawQuery("SELECT mnc, updated FROM op_meta WHERE src = ?", arrayOf(src)).use {
            buildMap { while (it.moveToNext()) put(it.getInt(0), it.getLong(1)) }
        }
        val fallback = info(source)?.updatedAtMillis ?: return own
        return cellsPerOperator(source).keys.associateWith { own[it] ?: fallback }
    }

    /** Cells stored per operator (MNC) for [source]. */
    fun cellsPerOperator(source: StationSource): Map<Int, Int> =
        readableDatabase.rawQuery("SELECT mnc, COUNT(*) FROM cells WHERE src = ? GROUP BY mnc", arrayOf(source.id.toString())).use {
            buildMap { while (it.moveToNext()) put(it.getInt(0), it.getInt(1)) }
        }

    fun clear(source: StationSource) {
        replace(source, null) { }
        writableDatabase.delete("op_meta", "src = ?", arrayOf(source.id.toString()))
        writableDatabase.delete("meta", "src = ?", arrayOf(source.id.toString()))
    }

    /** Batched inserts for one import. Sites are deduplicated by name + position. */
    class Writer internal constructor(db: SQLiteDatabase, private val source: StationSource) {
        private val siteStmt: SQLiteStatement =
            db.compileStatement("INSERT INTO sites(src, station, name, lat, lng, ext, bands) VALUES (?,?,?,?,?,?,?)")
        private val cellStmt: SQLiteStatement =
            db.compileStatement("INSERT INTO cells(src, mcc, mnc, rat, node, cid, area, pci, ch, site) VALUES (?,?,?,?,?,?,?,?,?,?)")
        private val siteIds = HashMap<String, Long>()
        var sites = 0; private set
        var cells = 0; private set

        fun add(cell: DbCell, externalId: String? = null, bands: List<String> = emptyList()) {
            val siteKey = cell.name + '|' + Math.round(cell.lat * 1e5) + '|' + Math.round(cell.lng * 1e5)
            val site = siteIds.getOrPut(siteKey) {
                siteStmt.clearBindings()
                siteStmt.bindLong(1, source.id.toLong())
                bindText(siteStmt, 2, cell.stationId)
                siteStmt.bindString(3, cell.name)
                siteStmt.bindDouble(4, cell.lat)
                siteStmt.bindDouble(5, cell.lng)
                bindText(siteStmt, 6, externalId)
                bindText(siteStmt, 7, bands.takeIf { it.isNotEmpty() }?.joinToString(","))
                sites++
                siteStmt.executeInsert()
            }
            cellStmt.clearBindings()
            cellStmt.bindLong(1, source.id.toLong())
            cellStmt.bindLong(2, cell.mcc.toLong())
            cellStmt.bindLong(3, cell.mnc.toLong())
            cellStmt.bindLong(4, cell.rat.ordinal.toLong())
            bindLong(cellStmt, 5, cell.node)
            bindLong(cellStmt, 6, cell.cid)
            bindLong(cellStmt, 7, cell.areaCode?.toLong())
            bindLong(cellStmt, 8, cell.pci?.toLong())
            bindLong(cellStmt, 9, cell.channel?.toLong())
            cellStmt.bindLong(10, site)
            cellStmt.executeInsert()
            cells++
        }

        private fun bindLong(s: SQLiteStatement, i: Int, v: Long?) = if (v == null) s.bindNull(i) else s.bindLong(i, v)
        private fun bindText(s: SQLiteStatement, i: Int, v: String?) = if (v == null) s.bindNull(i) else s.bindString(i, v)
    }
}
