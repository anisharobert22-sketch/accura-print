# Accura Print

The small Android app that lets the **Accura billing page** on a cafe's tablet
print bills to a thermal printer and pop the cash drawer.

Browsers cannot talk to printers. This app listens on `http://localhost:8080`
on the tablet; Accura (served over HTTPS) posts each bill there, and the app
drives the printer — over **Wi-Fi/LAN, Bluetooth, or USB** — using the
[DantSu ESC/POS library](https://github.com/DantSu/ESCPOS-ThermalPrinter-Android).

```
Accura (browser) ──HTTP──▶ Accura Print (localhost) ──▶ printer ──RJ11──▶ cash drawer
```

## Set up a tablet (once)

1. Install the APK (from this repo's Releases) — allow "install unknown apps".
2. Open **Accura Print**. Choose how the printer is connected:
   - **Wi-Fi / LAN** — enter the printer's IP (usually port `9100`). Recommended: nothing plugs into the tablet.
   - **Bluetooth** — pair the printer in Android Settings first; leave the name blank to use the first paired printer.
   - **USB** — plug in via OTG; tap **Test print** once and allow USB access.
3. Choose paper width (80 mm is the normal cafe bill).
   **Cut position** is how far the paper feeds before the blade fires. The
   cutter sits downstream of the print head, so the last lines printed are
   still behind the blade when printing stops — feed less than that gap and
   the cut lands inside the bill, leaving the footer on the next customer's
   receipt. 30 mm suits most printers; raise it if the cut is early, lower it
   if every bill has a long blank tail.
   **Printer warm-up** is how long to wait after the printer answers before
   sending anything. A receipt printer ignores everything it receives while
   its cutter is still running, which is how the first lines of a bill — the
   logo and the shop's name — go missing while the line below them prints
   perfectly. It only ever affects bills, never the test slip, because a bill
   follows a cut and a test slip is printed cold. 1200 ms suits most printers;
   raise it if the top of the bill is still being lost.
4. **Test print.** It prints exactly the way a bill does, so if this works, bills will.
5. **Save & start.** An ongoing notification appears. It restarts itself after a reboot.

## HTTP API

| Method | Path | Body |
| --- | --- | --- |
| GET | `/health` | JSON: connection type, columns per line, printer target |
| POST | `/print-bill` | JSON `{ "text": "<DantSu markup>", "openDrawer": bool, "cut": bool }` |
| POST | `/print` | raw ESC/POS bytes, or JSON `{ bytes: base64 }` — network printers only |

`text` uses DantSu's markup: each line starts with `[L]`, `[C]` or `[R]`;
`<b>`, `<u>`, `<font size='big'>` inside. `[L]Item[R]Rs.30` puts the price
flush right. Use `Rs.` — thermal printers cannot print `₹`.

`openDrawer: true` cuts the paper and kicks the drawer — send it only for
cash bills.

## Building

GitHub Actions builds the APK on every push to `main` (artifact) and on any
`v*` tag (public release). Nothing to install locally.

```bash
git tag v2.0.0 && git push origin v2.0.0
```

Release signing: add the four keystore secrets described in
`.github/workflows/build.yml`. **Back the keystore up** — losing it means the
app can never be updated in place.

## Licence

MIT. A fork of [escpos-print-bridge](https://github.com/beingretrogamer/escpos-print-bridge)
by beingretrogamer — see `LICENSE`.
