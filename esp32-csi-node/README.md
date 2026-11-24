# ESP32 CSI node for RF Sentinel WiFi Sense

A small WiFi CSI motion sensor that streams to RF Sentinel over your network. Flash one or more boards
and add each in the app; every board shows as its own motion signal.

## Flash
1. Arduino IDE with the **esp32** board package (Espressif), or PlatformIO.
2. Open `esp32-csi-node.ino`, set `WIFI_SSID` and `WIFI_PASS`.
3. Select your ESP32-S3 board, upload.
4. Open Serial Monitor at 115200; it prints the board's IP.

## Add in the app
RF Sentinel → long-press a WiFi access point → **WiFi Sense (motion)** → **Add CSI node** → enter the
board's IP, port **3333**. Repeat for more boards.

## Notes
- The board senses motion on the channel of the AP it joins — put it on the AP/band you want to watch.
- It's receive-only for sensing; it transmits only normal WiFi association traffic.
- Already run ESPectre/esp-csi firmware you prefer? Point it at the phone instead by emitting either this
  sketch's `CSI_DATA,...,[i,q,...]` line or a precomputed `SENSE,<seq>,<motion 0-100>[,detail]` line over
  TCP; the app accepts both.

## Flash from a browser (no toolchain)
Don't want to install Arduino? Flash the prebuilt binary straight from a desktop browser:

**https://schlaf3r.github.io/RFSentinel/**

Open it in **desktop Chrome or Edge** (Web Serial — not available on phones), plug in the ESP32-S3, and
click **Connect & Flash**. Still set your WiFi SSID/password first: either flash, then reflash after
editing the sketch, or edit and build your own binary. The page serves the firmware compiled from this
folder by CI.
