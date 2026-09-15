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
    ) {
        p(c).edit()
            .putString("printerIp", ip)
            .putInt("printerPort", port)
            .putInt("bridgePort", bridgePort)
            .putBoolean("loopbackOnly", loopbackOnly)
            .putString("connectionType", connectionType)
            .putString("bluetoothName", bluetoothName.trim())
            .putInt("paperWidthMm", if (paperWidthMm == 58) 58 else 80)
            .apply()
    }

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
