package ai.soulvai.accuraprint

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.DeviceConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import com.dantsu.escposprinter.connection.usb.UsbPrintersConnections

/**
 * The DantSu-backed path: the tablet renders a formatted bill and drives the
 * printer over whichever connection the shop configured.
 *
 * Why this exists alongside the raw-bytes path in BridgeServer:
 *
 *  - Bluetooth and USB printers. The raw path only knows TCP.
 *  - Formatting done here, once, for every printer. Accura sends a small
 *    markup ("[C]<b>Bill</b>", "[L]Tea x2[R]Rs.30") and the library turns it
 *    into the right ESC/POS for that printer, including the paper cut and
 *    the cash-drawer pulse. Accura never has to know a printer's quirks.
 *
 * Everything here runs through PrinterQueue.exclusive so a bill and a test
 * slip can never open two connections to the same printer at once.
 */
object Printers {

    /** Physical geometry DantSu needs. 80mm paper prints 72mm wide at 48 columns. */
    private data class Paper(val dpi: Int, val widthMm: Float, val columns: Int)

    private fun paperFor(widthMm: Int) =
        if (widthMm == 58) Paper(203, 48f, 32) else Paper(203, 72f, 48)

    /** How many characters fit on one line — Accura asks for this to lay out the bill. */
    fun columns(context: Context): Int = paperFor(Prefs.paperWidthMm(context)).columns

    /**
     * Prints [text] (DantSu markup). Blocking; throws with a message a cashier
     * can act on. Cuts when [cut] is set; when [openDrawer] is set the paper
     * is cut and the drawer kicked regardless of [cut], because that is the
     * only sequence the library exposes and a cash bill always wants both.
     */
    fun printFormatted(
        context: Context,
        text: String,
        openDrawer: Boolean,
        cut: Boolean,
        onLog: (String) -> Unit = {},
    ) {
        val paper = paperFor(Prefs.paperWidthMm(context))
        PrinterQueue.exclusive {
            val printer = connectWhenReady(context, paper, onLog)
            // How far the paper advances before the blade fires. It has to
            // clear the gap between the print head and the cutter, or the cut
            // lands inside the bill - see Prefs.DEF_CUT_FEED_MM.
            val feed = Prefs.cutFeedMm(context).toFloat()
            // A leading blank line, on every job.
            //
            // Partly a top margin, which a receipt wants anyway. Mostly a
            // sacrifice: if the printer is still settling and loses the first
            // line, it loses this one and not the shop's name. Costs ~3mm.
            val body = "[L]\n$text"
            try {
                when {
                    openDrawer -> printer.printFormattedTextAndOpenCashBox(body, feed)
                    cut -> printer.printFormattedTextAndCut(body, feed)
                    else -> printer.printFormattedText(body, feed)
                }
            } finally {
                // Always release the socket/RFCOMM channel, or the next job
                // finds the printer "busy" until the OS times it out.
                try { printer.disconnectPrinter() } catch (_: Exception) {}
            }
        }
    }

    /** A short slip proving this connection type reaches this printer. */
    fun testSlip(connectionLabel: String): String =
        "[C]<b>ACCURA PRINT</b>\n" +
        "[C]Test slip\n" +
        "[L]\n" +
        "[L]Connection: $connectionLabel\n" +
        "[L]If you can read this, the tablet\n" +
        "[L]can reach the printer.\n" +
        "[L]\n"

    // ── Connection factory ────────────────────────────────────────────

    /** Attempts to reach the printer, and how long to wait between them. */
    private const val CONNECT_ATTEMPTS = 4
    private const val CONNECT_BACKOFF_MS = 600L

    /** Quiet time between the socket opening and the first byte going down it. */
    private const val SETTLE_MS = 400L

    /**
     * Opens the printer and waits for it to be ready to listen.
     *
     * A network receipt printer is a single-session device that goes deaf
     * while its cutter runs. Connecting the instant the previous bill was cut
     * produced both of the faults seen at a real counter:
     *
     *  - "Unable to connect to TCP device" - the printer refused the socket
     *    outright, so the bill never printed and the till fell back to a
     *    browser dialog that had nothing behind it.
     *  - Bills printing with their first two lines missing - the shop's logo
     *    and name, gone, on every bill. The socket was accepted but the
     *    printer's input buffer was not ready, so the opening bytes went
     *    nowhere. It only ever struck bills, never the test slip, because a
     *    bill follows a cut and a test slip is printed cold - which is
     *    exactly what made it look like a bug in the bill's markup.
     *
     * So: retry the connection rather than giving up on the first refusal,
     * and once it is open, say nothing for a moment. A few hundred
     * milliseconds is invisible beside the seconds a bill takes to print.
     */
    private fun connectWhenReady(context: Context, paper: Paper, onLog: (String) -> Unit): EscPosPrinter {
        var last: Exception? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                val printer = EscPosPrinter(openConnection(context, onLog), paper.dpi, paper.widthMm, paper.columns)
                if (attempt > 0) onLog("Printer answered on attempt ${attempt + 1}")
                Thread.sleep(SETTLE_MS)
                return printer
            } catch (e: Exception) {
                last = e
                if (attempt < CONNECT_ATTEMPTS - 1) {
                    onLog("Printer busy (${e.message ?: e.javaClass.simpleName}) — retrying")
                    try { Thread.sleep(CONNECT_BACKOFF_MS) } catch (_: InterruptedException) {}
                }
            }
        }
        throw last ?: IllegalStateException("Could not reach the printer")
    }

    private fun openConnection(context: Context, onLog: (String) -> Unit): DeviceConnection =
        when (Prefs.connectionType(context)) {
            Prefs.CONN_BLUETOOTH -> bluetooth(context, onLog)
            Prefs.CONN_USB -> usb(context)
            // Milliseconds, despite the README's example suggesting seconds —
            // the library passes this straight to Socket.connect.
            else -> TcpConnection(Prefs.printerIp(context), Prefs.printerPort(context), 5000)
        }

    private fun bluetooth(context: Context, onLog: (String) -> Unit): DeviceConnection {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Bluetooth permission not granted — open Accura Print and allow it")
        }
        val wanted = Prefs.bluetoothName(context)
        if (wanted.isEmpty()) {
            return BluetoothPrintersConnections.selectFirstPaired()
                ?: throw IllegalStateException("No paired Bluetooth printer — pair it in Android Settings first")
        }
        val all = BluetoothPrintersConnections().list
            ?: throw IllegalStateException("Bluetooth is off or unavailable on this tablet")
        val match = all.firstOrNull { c ->
            val name = try { c.device?.name } catch (_: SecurityException) { null }
            name != null && name.contains(wanted, ignoreCase = true)
        }
        if (match == null) {
            val names = all.mapNotNull { c -> try { c.device?.name } catch (_: SecurityException) { null } }
            onLog("Paired printers: ${names.joinToString().ifEmpty { "(none)" }}")
            throw IllegalStateException("No paired printer named like \"$wanted\"")
        }
        return match
    }

    private fun usb(context: Context): DeviceConnection {
        val connection: UsbConnection = UsbPrintersConnections.selectFirstConnected(context)
            ?: throw IllegalStateException("No USB printer connected — check the OTG cable")
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IllegalStateException("USB is unavailable on this tablet")
        if (!manager.hasPermission(connection.device)) {
            // Permission is a one-time Android dialog, and only an Activity
            // can show it: MainActivity asks on Test print. A bill arriving
            // before that has happened gets a clear reason, not a hang.
            throw IllegalStateException("USB printer needs permission — open Accura Print and tap Test print once")
        }
        return connection
    }
}
