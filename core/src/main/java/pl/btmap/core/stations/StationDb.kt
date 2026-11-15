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
class StationDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "stations.db", null, 1) {

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
        db.execSQL("CREATE TABLE meta(src INTEGER PRIMARY KEY, updated INTEGER, sites INTEGER, cells INTEGER, detail TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun info(source: StationSource): SourceInfo? =
        readableDatabase.rawQuery("SELECT updated, sites, cells, detail FROM meta WHERE src = ?", arrayOf(source.id.toString())).use {
            if (it.moveToFirst()) SourceInfo(it.getLong(0), it.getInt(1), it.getInt(2), it.getString(3)) else null
        }

    /** Site serving [node] of the given network, with every channel seen on it. */
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

    fun clear(source: StationSource) {
        replace(source, null) { }
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
