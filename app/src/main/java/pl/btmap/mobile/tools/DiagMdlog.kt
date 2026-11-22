package pl.btmap.mobile.tools

import android.content.Context
import pl.btmap.core.diag.DiagCommands
import pl.btmap.core.diag.DiagLogCodes
import pl.btmap.core.diag.Hdlc
import java.io.File

/**
 * Second way to record modem logs: Qualcomm's `diag_mdlog`, the on-device logger, with a mask
 * file (.cfg: HDLC-framed DIAG requests, the format QXDM writes) that switches on the same LTE /
 * NR5G logs. It writes .qmdl files itself; they are copied into the app for sharing.
 */
object DiagMdlog {
    private val TOOLS = listOf("/vendor/bin/diag_mdlog", "/system/bin/diag_mdlog")
    private const val DIR = "/data/local/tmp/btmap_mdlog"
    private const val CFG = "/data/local/tmp/btmap_mask.cfg"
    private const val OUT = "/data/local/tmp/btmap_mdlog.txt"
    private const val MAX_FILE = "-50M"

    data class Result(val files: List<File>, val log: String)

    fun capture(context: Context, seconds: Int, lastItem: Int, onSecond: (Int) -> Unit): Result {
        val tool = TOOLS.firstOrNull { RootShell.exec("sh", "-c", "[ -x $it ] && echo yes").second.contains("yes") }
            ?: return Result(emptyList(), "brak diag_mdlog")
        val equip = DiagCommands.EQUIP_LTE_NR
        val codes = DiagLogCodes.NAMES.keys
        val cfg = File(context.cacheDir, "btmap_mask.cfg").apply {
            writeBytes(
                Hdlc.encode(DiagCommands.setMaskBits(equip, lastItem, codes)) +
                    Hdlc.encode(DiagCommands.setMaskBits(equip, lastItem + 1, codes)) +
                    Hdlc.encode(DiagCommands.eventReport(true)),
            )
        }
        val target = File(context.cacheDir, "mdlog").apply { deleteRecursively(); mkdirs() }
        val uid = android.os.Process.myUid()
        RootShell.exec(
            "sh", "-c",
            "$tool -k >/dev/null 2>&1; rm -rf $DIR; mkdir -p $DIR; cp ${cfg.absolutePath} $CFG; chmod 644 $CFG; " +
                "(nohup $tool -f $CFG -o $DIR -c >$OUT 2>&1 &)",
        )
        for (s in 1..seconds) {
            Thread.sleep(1_000)
            onSecond(s)
        }
        val log = RootShell.exec(
            "sh", "-c",
            "$tool -k >/dev/null 2>&1; sleep 2; pkill -f diag_mdlog; ls -lR $DIR 2>&1 | head -25; echo '-- log:'; head -c 1500 $OUT; " +
                "find $DIR -type f -size $MAX_FILE -exec cp {} ${target.absolutePath}/ \\; ; chown -R $uid:$uid ${target.absolutePath}; chmod 644 ${target.absolutePath}/* 2>/dev/null; " +
                "rm -rf $DIR $CFG $OUT",
            timeoutSeconds = 40,
        ).second.trim()
        cfg.delete()
        return Result(target.listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty(), log)
    }
}
