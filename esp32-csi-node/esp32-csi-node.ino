/*
 * RF Sentinel — ESP32-S3 WiFi CSI node
 * ------------------------------------
 * A networked motion sensor for RF Sentinel's WiFi Sense. The board joins your WiFi, captures Channel
 * State Information from the packets it receives, and streams it over TCP to the phone, which turns the
 * per-subcarrier wobble into a motion score. Receive-only: it transmits only normal WiFi association
 * traffic, nothing is injected.
 *
 * In RF Sentinel: open a WiFi access point's menu > WiFi Sense (motion) > Add CSI node, and enter this
 * board's IP and port 3333. Add as many boards as you like; each is its own motion signal.
 *
 * Board: any ESP32-S3 (also plain ESP32/C5) on the Arduino-ESP32 core. The node senses motion on the
 * channel of whatever AP it joins, so put it on the same AP/band you want to watch.
 *
 * Flash: set WIFI_SSID / WIFI_PASS below, select your ESP32-S3 board, upload. The serial monitor prints
 * the IP to enter in the app.
 *
 * Line format sent to the app (one per CSI-bearing packet):
 *   CSI_DATA,<seq>,<rssi>,<len>,[i0,q0,i1,q1,...]
 * The app reads the bracketed array (interleaved imag,real int8 pairs) and computes motion itself.
 */

#include <WiFi.h>
#include "esp_wifi.h"

const char* WIFI_SSID = "YOUR_WIFI";
const char* WIFI_PASS = "YOUR_PASSWORD";
const uint16_t PORT    = 3333;

WiFiServer server(PORT);
WiFiClient client;
volatile uint32_t seq = 0;

// Built in the CSI callback, drained in loop() (the callback must stay short and must not do TCP I/O).
static char lineBuf[1600];
volatile bool lineReady = false;

void onCsi(void* ctx, wifi_csi_info_t* info) {
  if (lineReady || !info || !info->buf || info->len <= 0) return;   // drop while the last line is still pending
  int len = info->len;
  if (len > 384) len = 384;                                         // guard the buffer
  int8_t* d = info->buf;
  int rssi = info->rx_ctrl.rssi;
  int n = snprintf(lineBuf, sizeof(lineBuf), "CSI_DATA,%u,%d,%d,[", (unsigned)seq++, rssi, len);
  for (int i = 0; i < len && n < (int)sizeof(lineBuf) - 12; i++) {
    n += snprintf(lineBuf + n, sizeof(lineBuf) - n, "%d%s", d[i], (i + 1 < len) ? "," : "");
  }
  snprintf(lineBuf + n, sizeof(lineBuf) - n, "]\n");
  lineReady = true;
}

void startCsi() {
  // Zero-init then set members by name, so field order/additions across ESP32 core versions don't
  // break the build. These names are the long-standing ones (esp-idf 4.x and 5.x).
  wifi_csi_config_t cfg = {};
  cfg.lltf_en = true;
  cfg.htltf_en = true;
  cfg.stbc_htltf2_en = true;
  cfg.ltf_merge_en = true;
  cfg.channel_filter_en = true;
  cfg.manu_scale = false;
  cfg.shift = 0;
  esp_wifi_set_csi_config(&cfg);
  esp_wifi_set_csi_rx_cb(&onCsi, NULL);
  esp_wifi_set_csi(true);
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Joining WiFi");
  while (WiFi.status() != WL_CONNECTED) { delay(400); Serial.print("."); }
  Serial.println();
  Serial.print("CSI node ready at ");
  Serial.print(WiFi.localIP());
  Serial.printf(":%u  (enter this in RF Sentinel)\n", PORT);
  server.begin();
  startCsi();
}

void loop() {
  if (!client || !client.connected()) {
    client = server.available();
    if (client) {
      client.setNoDelay(true);
      Serial.println("app connected");
    }
  }
  // The app may send "CH <n>\n"; with the node associated to an AP the channel follows that AP, so we
  // just read and discard it to keep the socket drained.
  if (client && client.available()) { while (client.available()) client.read(); }

  if (lineReady) {
    if (client && client.connected()) client.print(lineBuf);
    lineReady = false;
  }
}
