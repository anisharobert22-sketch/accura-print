package ai.soulvai.accuraprint

import android.content.Context

/** The handful of settings the shop actually changes. */
object Prefs {
    private const val FILE = "printbridge"

    const val DEF_PRINTER_IP = "192.168.1.200"
    const val DEF_PRINTER_PORT = 9100
    const val DEF_BRIDGE_PORT = 8080

    /** How the tablet reaches the printer. Network is the recommended default. */
    const val CONN_WIFI = "wifi"
    const val CONN_BLUETOOTH = "bluetooth"
    const val CONN_USB = "usb"
    const val DEF_CONNECTION = CONN_WIFI

    /** 80mm is the normal cafe bill; 58mm is the small portable printer. */
    const val DEF_PAPER_MM = 80

    /**
     * How far the paper advances before the cutter fires, in millimetres.
     *
     * The cutter sits downstream of the print head, so the last lines printed
     * are still behind the blade when printing stops. Feed less than that gap
     * and the cut lands in the middle of the bill: on a real TVS RP 3200 at
     * 20mm the cut fell above "Thank you, visit again", and every receipt
     * carried the previous customer's footer at the top of it.
     *
     * The gap differs by printer model and nobody publishes it, so this is a
     * setting rather than a constant - a shop can nudge it without waiting
     * for a new build. 30mm clears the models we have seen; too high only
     * wastes a little paper, too low cuts into the bill.
     */
    const val DEF_CUT_FEED_MM = 30
    const val MIN_CUT_FEED_MM = 5
    const val MAX_CUT_FEED_MM = 60

    /**
     * Quiet time between the printer accepting the socket and the first byte
     * going down it, in milliseconds.
     *
     * A network receipt printer goes deaf while its cutter runs, and discards
     * whatever arrives in that window. At a real counter this ate the first
     * two lines of every bill - the shop's logo and its name - while the
     * address one line below printed perfectly, and it never touched the test
     * slip, because a bill follows a cut and a test slip is printed cold.
     *
     * How long a cutter takes is per-model and unpublished, so this is a
     * setting: if lines are still going missing, raise it. Too high only
     * makes each bill slower by that much.
     */
    const val DEF_WARMUP_MS = 1200
    const val MIN_WARMUP_MS = 0
    const val MAX_WARMUP_MS = 5000

    fun printerIp(c: Context): String = p(c).getString("printerIp", DEF_PRINTER_IP)!!
    fun printerPort(c: Context): Int = p(c).getInt("printerPort", DEF_PRINTER_PORT)
    fun bridgePort(c: Context): Int = p(c).getInt("bridgePort", DEF_BRIDGE_PORT)
    /** Loopback by default: the browser using this bridge is on this device. */
    fun loopbackOnly(c: Context): Boolean = p(c).getBoolean("loopbackOnly", true)

    fun connectionType(c: Context): String = p(c).getString("connectionType", DEF_CONNECTION)!!
    /**
     * Empty means "the first paired printer". A name is only needed when the
     * tablet is paired to more than one Bluetooth printer, which is rare.
     */
    fun bluetoothName(c: Context): String = p(c).getString("bluetoothName", "")!!
    fun paperWidthMm(c: Context): Int = p(c).getInt("paperWidthMm", DEF_PAPER_MM)
    fun cutFeedMm(c: Context): Int = p(c).getInt("cutFeedMm", DEF_CUT_FEED_MM).coerceIn(MIN_CUT_FEED_MM, MAX_CUT_FEED_MM)
    fun warmupMs(c: Context): Int = p(c).getInt("warmupMs", DEF_WARMUP_MS).coerceIn(MIN_WARMUP_MS, MAX_WARMUP_MS)

    /**
     * Whether the bridge should come back on its own after a reboot.
     *
     * Tracks the last deliberate choice: pressing Stop meant stop, and a
     * restart should not quietly undo that. Defaults to true so a tablet that
     * has never been touched still comes up printing.
     */
    fun autoStart(c: Context): Boolean = p(c).getBoolean("autoStart", true)

    fun setAutoStart(c: Context, on: Boolean) {
        p(c).edit().putBoolean("autoStart", on).apply()
    }

    fun save(
        c: Context,
        ip: String,
        port: Int,
        bridgePort: Int,
        loopbackOnly: Boolean,
        connectionType: String = connectionType(c),
        bluetoothName: String = bluetoothName(c),
        paperWidthMm: Int = paperWidthMm(c),
        cutFeedMm: Int = cutFeedMm(c),
        warmupMs: Int = warmupMs(c),
    ) {
        p(c).edit()
            .putString("printerIp", ip)
            .putInt("printerPort", port)
            .putInt("bridgePort", bridgePort)
            .putBoolean("loopbackOnly", loopbackOnly)
            .putString("connectionType", connectionType)
            .putString("bluetoothName", bluetoothName.trim())
            .putInt("paperWidthMm", if (paperWidthMm == 58) 58 else 80)
            .putInt("cutFeedMm", cutFeedMm.coerceIn(MIN_CUT_FEED_MM, MAX_CUT_FEED_MM))
            .putInt("warmupMs", warmupMs.coerceIn(MIN_WARMUP_MS, MAX_WARMUP_MS))
            .apply()
    }

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
