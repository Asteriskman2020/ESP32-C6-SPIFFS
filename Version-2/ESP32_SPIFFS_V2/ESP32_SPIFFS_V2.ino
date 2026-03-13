/*
 * ESP32-C6 SPIFFS Data Logger V2
 * Hardware: AHT20 + BMP280 (SDA=GPIO20, SCL=GPIO18), NeoPixel (GPIO8)
 * BLE: NimBLE-Arduino library
 * File rotation: 5 files x 20 records each
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_AHTX0.h>
#include <Adafruit_BMP280.h>
#include <Adafruit_NeoPixel.h>
#include <SPIFFS.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoOTA.h>
#include <time.h>
#include <NimBLEDevice.h>

// ─── Pin definitions ───────────────────────────────────────────────────────
#define SDA_PIN        20
#define SCL_PIN        18
#define NEO_PIN         8
#define NEO_COUNT       1
#define NEO_BRIGHTNESS 50

// ─── File rotation ─────────────────────────────────────────────────────────
#define MAX_FILES      5
#define RECORDS_PER_FILE 20

// ─── BLE UUIDs ─────────────────────────────────────────────────────────────
#define SERVICE_UUID   "bc000001-0000-1000-8000-00805f9b34fb"
#define CMD_UUID       "bc000002-0000-1000-8000-00805f9b34fb"
#define FILE_UUID      "bc000003-0000-1000-8000-00805f9b34fb"
#define STAT_UUID      "bc000004-0000-1000-8000-00805f9b34fb"

// ─── AP fallback ───────────────────────────────────────────────────────────
#define AP_SSID "ESP32C6-SPIFFS"
#define AP_PASS "12345678"

// ─── LED modes ─────────────────────────────────────────────────────────────
enum LedMode { LED_EMPTY, LED_FILLING, LED_FULL, LED_CLEARED };
LedMode ledMode = LED_EMPTY;
unsigned long ledLastToggle = 0;
bool ledState = false;
unsigned long clearedStartMs = 0;

// ─── Global objects ────────────────────────────────────────────────────────
Adafruit_AHTX0 aht;
Adafruit_BMP280 bmp;
Adafruit_NeoPixel strip(NEO_COUNT, NEO_PIN, NEO_GRB + NEO_KHZ800);
Preferences prefs;
WebServer webServer(80);

bool ahtOk = false;
bool bmpOk = false;
bool wifiConnected = false;

// ─── NVS config ────────────────────────────────────────────────────────────
String cfgSsid, cfgPass, cfgMqttIp, cfgMqttUser, cfgMqttPass, cfgNtpServer;
int    cfgMqttPort = 1883;
uint16_t curFile = 1;
uint16_t curCnt  = 0;

// ─── BLE pointers ──────────────────────────────────────────────────────────
NimBLEServer*         pServer      = nullptr;
NimBLECharacteristic* pFileChr     = nullptr;
NimBLECharacteristic* pStatChr     = nullptr;
bool bleConnected = false;

// ─── Sampling ──────────────────────────────────────────────────────────────
unsigned long lastSampleMs = 0;

// ─── Forward declarations ──────────────────────────────────────────────────
void setupWiFi();
void setupOTA();
void setupWebServer();
void setupBLE();
void updateLedMode(LedMode m);
void handleLed();
void sampleAndStore();
String getFilePath(uint16_t n);
bool dataFileExists(uint16_t n);
bool anyDataFileExists();
int  countRecordsInFile(const String& path);
void appendRecord(const String& path, const String& record);
time_t getUnixTime();
void notifyStat(const String& msg);
void notifyFile(const String& data);
void sendFileInChunks(const String& path);
void sendFileListJson();
void loadNvsConfig();
void saveNvsConfig();
void loadNvsCursor();
void saveNvsCursor();
void deleteAllDataFiles();

// ═══════════════════════════════════════════════════════════════════════════
// LED helpers
// ═══════════════════════════════════════════════════════════════════════════
void setPixel(uint8_t r, uint8_t g, uint8_t b) {
  strip.setPixelColor(0, strip.Color(r, g, b));
  strip.show();
}

void updateLedMode(LedMode m) {
  ledMode = m;
  ledLastToggle = millis();
  ledState = true;
  if (m == LED_CLEARED) clearedStartMs = millis();
}

void handleLed() {
  unsigned long now = millis();

  // LED_CLEARED holds for 6 seconds then → LED_EMPTY
  if (ledMode == LED_CLEARED && now - clearedStartMs >= 6000) {
    updateLedMode(LED_EMPTY);
    return;
  }

  uint32_t halfPeriod;
  uint8_t  r = 0, g = 0, b = 0;

  switch (ledMode) {
    case LED_EMPTY:   halfPeriod = 100;  r = 255; break;          // fast RED
    case LED_FILLING: halfPeriod = 200;  g = 255; break;          // quick GREEN
    case LED_FULL:    halfPeriod = 1000; g = 255; break;          // slow GREEN
    case LED_CLEARED: halfPeriod = 2000; r = 255; break;          // long RED
  }

  if (now - ledLastToggle >= halfPeriod) {
    ledLastToggle = now;
    ledState = !ledState;
    if (ledState) setPixel(r, g, b);
    else          setPixel(0, 0, 0);
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// NTP / time
// ═══════════════════════════════════════════════════════════════════════════
time_t getUnixTime() {
  time_t t = time(nullptr);
  if (t > 1700000000UL) return t;
  return (time_t)(millis() / 1000UL);
}

// ═══════════════════════════════════════════════════════════════════════════
// File helpers
// ═══════════════════════════════════════════════════════════════════════════
String getFilePath(uint16_t n) {
  return "/data" + String(n) + ".json";
}

bool dataFileExists(uint16_t n) {
  return SPIFFS.exists(getFilePath(n));
}

bool anyDataFileExists() {
  for (uint16_t i = 1; i <= MAX_FILES; i++) {
    if (dataFileExists(i)) return true;
  }
  return false;
}

int countRecordsInFile(const String& path) {
  if (!SPIFFS.exists(path)) return 0;
  File f = SPIFFS.open(path, "r");
  if (!f) return 0;
  String content = f.readString();
  f.close();
  int count = 0;
  int idx = 0;
  while ((idx = content.indexOf("\"i\":", idx)) != -1) { count++; idx += 4; }
  return count;
}

void appendRecord(const String& path, const String& record) {
  if (!SPIFFS.exists(path)) {
    // Create new file
    File f = SPIFFS.open(path, "w");
    if (!f) { Serial.println("ERR: open for write"); return; }
    f.print("[");
    f.print(record);
    f.print("]");
    f.close();
  } else {
    // Read existing, strip closing ], append
    File f = SPIFFS.open(path, "r");
    if (!f) { Serial.println("ERR: open for read"); return; }
    String content = f.readString();
    f.close();
    // Remove trailing ]
    int endIdx = content.lastIndexOf(']');
    if (endIdx >= 0) content = content.substring(0, endIdx);
    content += "," + record + "]";
    File fw = SPIFFS.open(path, "w");
    if (!fw) { Serial.println("ERR: open for rewrite"); return; }
    fw.print(content);
    fw.close();
  }
}

void deleteAllDataFiles() {
  for (uint16_t i = 1; i <= MAX_FILES; i++) {
    String p = getFilePath(i);
    if (SPIFFS.exists(p)) SPIFFS.remove(p);
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// NVS helpers
// ═══════════════════════════════════════════════════════════════════════════
void loadNvsConfig() {
  prefs.begin("cfg", true);
  cfgSsid      = prefs.getString("ssid",      "");
  cfgPass      = prefs.getString("pass",      "");
  cfgMqttIp    = prefs.getString("mqttIp",    "");
  cfgMqttPort  = prefs.getInt("mqttPort",     1883);
  cfgMqttUser  = prefs.getString("mqttUser",  "");
  cfgMqttPass  = prefs.getString("mqttPass",  "");
  cfgNtpServer = prefs.getString("ntpServer", "pool.ntp.org");
  prefs.end();
}

void saveNvsConfig() {
  prefs.begin("cfg", false);
  prefs.putString("ssid",      cfgSsid);
  prefs.putString("pass",      cfgPass);
  prefs.putString("mqttIp",    cfgMqttIp);
  prefs.putInt   ("mqttPort",  cfgMqttPort);
  prefs.putString("mqttUser",  cfgMqttUser);
  prefs.putString("mqttPass",  cfgMqttPass);
  prefs.putString("ntpServer", cfgNtpServer);
  prefs.end();
}

void loadNvsCursor() {
  prefs.begin("cfg", true);
  curFile = prefs.getUShort("curFile", 1);
  curCnt  = prefs.getUShort("curCnt",  0);
  prefs.end();
  if (curFile < 1 || curFile > MAX_FILES) curFile = 1;
  // Validate against actual file
  String p = getFilePath(curFile);
  int actual = countRecordsInFile(p);
  if (actual > 0) curCnt = actual;
}

void saveNvsCursor() {
  prefs.begin("cfg", false);
  prefs.putUShort("curFile", curFile);
  prefs.putUShort("curCnt",  curCnt);
  prefs.end();
}

// ═══════════════════════════════════════════════════════════════════════════
// Sampling
// ═══════════════════════════════════════════════════════════════════════════
void sampleAndStore() {
  float tempAht = 0, hum = 0, tempBmp = 0, pres = 0;

  if (ahtOk) {
    sensors_event_t hEvent, tEvent;
    aht.getEvent(&hEvent, &tEvent);
    tempAht = tEvent.temperature;
    hum     = hEvent.relative_humidity;
  }
  if (bmpOk) {
    tempBmp = bmp.readTemperature();
    pres    = bmp.readPressure() / 100.0f;
  }

  time_t ts = getUnixTime();

  // Build JSON record
  char rec[128];
  snprintf(rec, sizeof(rec),
    "{\"i\":%u,\"ts\":%lu,\"t\":%.1f,\"h\":%.1f,\"p\":%.1f,\"tb\":%.1f}",
    (unsigned)(curCnt),
    (unsigned long)ts,
    tempAht, hum, pres, tempBmp);

  String path = getFilePath(curFile);
  appendRecord(path, String(rec));
  curCnt++;

  Serial.printf("Stored: file=%d cnt=%d ts=%lu\n", curFile, curCnt, (unsigned long)ts);

  if (curCnt >= RECORDS_PER_FILE) {
    updateLedMode(LED_FULL);
    curFile++;
    if (curFile > MAX_FILES) curFile = 1;
    // Delete overwritten file
    String nextPath = getFilePath(curFile);
    if (SPIFFS.exists(nextPath)) SPIFFS.remove(nextPath);
    curCnt = 0;
  } else {
    updateLedMode(LED_FILLING);
  }

  saveNvsCursor();
}

// ═══════════════════════════════════════════════════════════════════════════
// BLE helpers
// ═══════════════════════════════════════════════════════════════════════════
void notifyStat(const String& msg) {
  if (!pStatChr) return;
  pStatChr->setValue(msg.c_str());
  if (bleConnected) pStatChr->notify();
}

void notifyFile(const String& data) {
  if (!pFileChr) return;
  pFileChr->setValue(data.c_str());
  if (bleConnected) pFileChr->notify();
}

void sendFileInChunks(const String& path) {
  if (!SPIFFS.exists(path)) {
    notifyFile("ERROR:FILE_NOT_FOUND");
    notifyFile("END");
    return;
  }
  File f = SPIFFS.open(path, "r");
  if (!f) {
    notifyFile("ERROR:OPEN_FAIL");
    notifyFile("END");
    return;
  }
  const int chunkSize = 200;
  char buf[chunkSize + 1];
  while (f.available()) {
    int n = f.readBytes(buf, chunkSize);
    buf[n] = '\0';
    notifyFile(String(buf));
    delay(30);
  }
  f.close();
  notifyFile("END");
}

void sendFileListJson() {
  String json = "[";
  bool first = true;
  for (uint16_t i = 1; i <= MAX_FILES; i++) {
    String p = getFilePath(i);
    if (SPIFFS.exists(p)) {
      File f = SPIFFS.open(p, "r");
      size_t sz = f ? f.size() : 0;
      if (f) f.close();
      int recs = countRecordsInFile(p);
      if (!first) json += ",";
      json += "{\"name\":\"data" + String(i) + ".json\",\"size\":" + String(sz) + ",\"records\":" + String(recs) + "}";
      first = false;
    }
  }
  json += "]";
  notifyFile(json);
  delay(30);
  notifyFile("END");
}

// ═══════════════════════════════════════════════════════════════════════════
// BLE Callbacks
// ═══════════════════════════════════════════════════════════════════════════
class MyServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pSvr, NimBLEConnInfo& connInfo) override {
    bleConnected = true;
    Serial.println("BLE connected");
    notifyStat("CONNECTED");
  }
  void onDisconnect(NimBLEServer* pSvr, NimBLEConnInfo& connInfo, int reason) override {
    bleConnected = false;
    Serial.println("BLE disconnected, restarting advertising");
    NimBLEDevice::startAdvertising();
  }
};

class CmdCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pChr, NimBLEConnInfo& connInfo) override {
    String cmd = pChr->getValue().c_str();
    cmd.trim();
    Serial.printf("BLE CMD: %s\n", cmd.c_str());

    if (cmd == "LISTFILES") {
      sendFileListJson();
    }
    else if (cmd.startsWith("READFILE:")) {
      String fname = cmd.substring(9);
      fname.trim();
      String path = "/" + fname;
      sendFileInChunks(path);
    }
    else if (cmd.startsWith("CLEARFILE:")) {
      String fname = cmd.substring(10);
      fname.trim();
      String path = "/" + fname;
      if (SPIFFS.exists(path)) {
        SPIFFS.remove(path);
        notifyStat("CLEARED");
        // If cleared current file, reset counter
        String curPath = getFilePath(curFile);
        if (path == curPath) { curCnt = 0; saveNvsCursor(); }
      } else {
        notifyStat("ERROR:NOT_FOUND");
      }
      updateLedMode(LED_CLEARED);
    }
    else if (cmd == "CLEARALL") {
      deleteAllDataFiles();
      curFile = 1; curCnt = 0;
      saveNvsCursor();
      notifyStat("CLEARED");
      updateLedMode(LED_CLEARED);
    }
    else {
      notifyStat("ERROR:UNKNOWN_CMD");
    }
  }
};

// ═══════════════════════════════════════════════════════════════════════════
// WiFi setup
// ═══════════════════════════════════════════════════════════════════════════
void setupWiFi() {
  if (cfgSsid.length() == 0) {
    Serial.println("No SSID, starting AP");
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("AP IP: %s\n", WiFi.softAPIP().toString().c_str());
    return;
  }
  WiFi.begin(cfgSsid.c_str(), cfgPass.c_str());
  unsigned long t = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t < 10000) {
    delay(500);
    Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    wifiConnected = true;
    Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());
  } else {
    Serial.println("\nWiFi failed, starting AP");
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("AP IP: %s\n", WiFi.softAPIP().toString().c_str());
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// OTA setup
// ═══════════════════════════════════════════════════════════════════════════
void setupOTA() {
  ArduinoOTA.setHostname("esp32c6-spiffs-v2");
  ArduinoOTA.onStart([]() { Serial.println("OTA Start"); });
  ArduinoOTA.onEnd([]()   { Serial.println("OTA End");   });
  ArduinoOTA.onProgress([](unsigned int p, unsigned int t) {
    Serial.printf("OTA %u%%\r", (p * 100) / t);
  });
  ArduinoOTA.onError([](ota_error_t err) {
    Serial.printf("OTA Error[%u]\n", err);
  });
  ArduinoOTA.begin();
}

// ═══════════════════════════════════════════════════════════════════════════
// Web Server HTML (PROGMEM)
// ═══════════════════════════════════════════════════════════════════════════
const char CONFIG_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ESP32-C6 SPIFFS V2</title>
<style>
body{font-family:Arial,sans-serif;background:#fff8f0;margin:0;padding:16px;}
h1{color:#e65c00;font-size:1.4em;}
h2{color:#bf4000;font-size:1.1em;margin-top:20px;}
.card{background:#fff;border-radius:8px;padding:16px;margin-bottom:14px;box-shadow:0 2px 4px rgba(0,0,0,0.12);}
table{width:100%;border-collapse:collapse;margin-bottom:8px;}
td{padding:4px 8px;font-size:0.9em;}
td:first-child{font-weight:bold;color:#666;width:40%;}
label{display:block;margin-top:10px;font-weight:bold;color:#555;font-size:0.9em;}
input[type=text],input[type=password],input[type=number]{width:100%;box-sizing:border-box;padding:8px;border:1px solid #ccc;border-radius:4px;margin-top:4px;font-size:0.9em;}
.btn{background:#e65c00;color:#fff;border:none;padding:10px 20px;border-radius:4px;font-size:1em;cursor:pointer;margin-top:12px;}
.btn:hover{background:#bf4000;}
.ok{color:green;} .err{color:red;}
</style>
</head>
<body>
<h1>ESP32-C6 SPIFFS Data Logger V2</h1>
<div class="card">
<h2>System Status</h2>
<table>
<tr><td>WiFi</td><td class="%WIFICLASS%">%WIFISTATUS%</td></tr>
<tr><td>IP</td><td>%IPADDR%</td></tr>
<tr><td>NTP</td><td class="%NTPCLASS%">%NTPSTATUS%</td></tr>
<tr><td>Current File</td><td>data%CURFILE%.json</td></tr>
<tr><td>Records in File</td><td>%CURCNT% / 20</td></tr>
<tr><td>Uptime</td><td>%UPTIME%s</td></tr>
</table>
</div>
<div class="card">
<h2>WiFi & Server Configuration</h2>
<form method="POST" action="/save">
<label>SSID</label><input type="text" name="ssid" value="%SSID%">
<label>Password</label><input type="password" name="pass" value="">
<label>MQTT IP</label><input type="text" name="mqttIp" value="%MQTTIP%">
<label>MQTT Port</label><input type="number" name="mqttPort" value="%MQTTPORT%">
<label>MQTT User</label><input type="text" name="mqttUser" value="%MQTTUSER%">
<label>MQTT Password</label><input type="password" name="mqttPass" value="">
<label>NTP Server</label><input type="text" name="ntpServer" value="%NTPSERVER%">
<br><input type="submit" class="btn" value="Save &amp; Reboot">
</form>
</div>
<p><a href="/files">SPIFFS File Browser</a></p>
</body>
</html>
)rawliteral";

const char FILES_HTML_TOP[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SPIFFS Browser</title>
<style>
body{font-family:Arial,sans-serif;background:#fff8f0;margin:0;padding:16px;}
h1{color:#e65c00;}
.card{background:#fff;border-radius:8px;padding:16px;margin-bottom:14px;box-shadow:0 2px 4px rgba(0,0,0,0.12);}
table{width:100%;border-collapse:collapse;}
th{background:#e65c00;color:#fff;padding:8px;}
td{padding:8px;border-bottom:1px solid #eee;font-size:0.9em;}
.btn{padding:5px 10px;border:none;border-radius:4px;cursor:pointer;font-size:0.85em;}
.dl{background:#388E3C;color:#fff;} .dl:hover{background:#2e7d32;}
.del{background:#D32F2F;color:#fff;} .del:hover{background:#b71c1c;}
</style>
</head>
<body>
<h1>SPIFFS File Browser</h1>
<p><a href="/">&larr; Back to Config</a></p>
<div class="card">
<table>
<tr><th>Filename</th><th>Size (bytes)</th><th colspan="2">Actions</th></tr>
)rawliteral";

const char FILES_HTML_BOT[] PROGMEM = R"rawliteral(
</table>
</div>
</body>
</html>
)rawliteral";

String templateReplace(String html) {
  bool ntpOk = (time(nullptr) > 1700000000UL);
  html.replace("%WIFICLASS%",  wifiConnected ? "ok" : "err");
  html.replace("%WIFISTATUS%", wifiConnected ? WiFi.localIP().toString() : "Not connected");
  html.replace("%IPADDR%",     wifiConnected ? WiFi.localIP().toString() : WiFi.softAPIP().toString());
  html.replace("%NTPCLASS%",   ntpOk ? "ok" : "err");
  html.replace("%NTPSTATUS%",  ntpOk ? "Synced" : "Not synced");
  html.replace("%CURFILE%",    String(curFile));
  html.replace("%CURCNT%",     String(curCnt));
  html.replace("%UPTIME%",     String(millis() / 1000));
  html.replace("%SSID%",       cfgSsid);
  html.replace("%MQTTIP%",     cfgMqttIp);
  html.replace("%MQTTPORT%",   String(cfgMqttPort));
  html.replace("%MQTTUSER%",   cfgMqttUser);
  html.replace("%NTPSERVER%",  cfgNtpServer);
  return html;
}

// ═══════════════════════════════════════════════════════════════════════════
// Web Server setup
// ═══════════════════════════════════════════════════════════════════════════
void setupWebServer() {
  // GET /
  webServer.on("/", HTTP_GET, []() {
    String html = templateReplace(String(FPSTR(CONFIG_HTML)));
    webServer.send(200, "text/html", html);
  });

  // GET /files
  webServer.on("/files", HTTP_GET, []() {
    String out = FPSTR(FILES_HTML_TOP);
    File root = SPIFFS.open("/");
    File file = root.openNextFile();
    while (file) {
      String name = String(file.name());
      size_t sz   = file.size();
      file.close();
      out += "<tr><td>" + name + "</td><td>" + String(sz) + "</td>";
      out += "<td><a class='btn dl' href='/download?f=" + name + "'>Download</a></td>";
      out += "<td><form method='POST' action='/delete' style='display:inline'>"
             "<input type='hidden' name='f' value='" + name + "'>"
             "<button class='btn del' type='submit'>Delete</button></form></td></tr>";
      file = root.openNextFile();
    }
    out += FPSTR(FILES_HTML_BOT);
    webServer.send(200, "text/html", out);
  });

  // GET /download
  webServer.on("/download", HTTP_GET, []() {
    if (!webServer.hasArg("f")) { webServer.send(400, "text/plain", "Missing f"); return; }
    String path = webServer.arg("f");
    if (!path.startsWith("/")) path = "/" + path;
    if (!SPIFFS.exists(path)) { webServer.send(404, "text/plain", "Not found"); return; }
    File f = SPIFFS.open(path, "r");
    webServer.streamFile(f, "application/octet-stream");
    f.close();
  });

  // POST /delete
  webServer.on("/delete", HTTP_POST, []() {
    if (!webServer.hasArg("f")) { webServer.send(400, "text/plain", "Missing f"); return; }
    String path = webServer.arg("f");
    if (!path.startsWith("/")) path = "/" + path;
    if (SPIFFS.exists(path)) SPIFFS.remove(path);
    webServer.sendHeader("Location", "/files", true);
    webServer.send(302, "text/plain", "");
  });

  // POST /save
  webServer.on("/save", HTTP_POST, []() {
    if (webServer.hasArg("ssid"))      cfgSsid      = webServer.arg("ssid");
    if (webServer.hasArg("pass") && webServer.arg("pass").length() > 0)
                                        cfgPass      = webServer.arg("pass");
    if (webServer.hasArg("mqttIp"))    cfgMqttIp    = webServer.arg("mqttIp");
    if (webServer.hasArg("mqttPort"))  cfgMqttPort  = webServer.arg("mqttPort").toInt();
    if (webServer.hasArg("mqttUser"))  cfgMqttUser  = webServer.arg("mqttUser");
    if (webServer.hasArg("mqttPass") && webServer.arg("mqttPass").length() > 0)
                                        cfgMqttPass  = webServer.arg("mqttPass");
    if (webServer.hasArg("ntpServer")) cfgNtpServer = webServer.arg("ntpServer");
    saveNvsConfig();
    webServer.send(200, "text/html",
      "<html><body style='font-family:Arial;padding:20px'>"
      "<h2 style='color:green'>Saved! Rebooting...</h2>"
      "</body></html>");
    delay(1500);
    ESP.restart();
  });

  webServer.begin();
  Serial.println("Web server started");
}

// ═══════════════════════════════════════════════════════════════════════════
// BLE setup
// ═══════════════════════════════════════════════════════════════════════════
void setupBLE() {
  NimBLEDevice::init("ESP32-C6 SPIFFS");
  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  // CMD char — WRITE + WRITE_NR
  NimBLECharacteristic* pCmdChr = pService->createCharacteristic(
    CMD_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
  );
  pCmdChr->setCallbacks(new CmdCallbacks());

  // FILE char — READ + NOTIFY
  pFileChr = pService->createCharacteristic(
    FILE_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
  );

  // STAT char — READ + NOTIFY
  pStatChr = pService->createCharacteristic(
    STAT_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
  );
  pStatChr->setValue("IDLE");

  pService->start();

  NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->enableScanResponse(true);
  NimBLEDevice::startAdvertising();
  Serial.println("BLE advertising started");
}

// ═══════════════════════════════════════════════════════════════════════════
// Setup
// ═══════════════════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n=== ESP32-C6 SPIFFS Data Logger V2 ===");

  // 1. NeoPixel
  strip.begin();
  strip.setBrightness(NEO_BRIGHTNESS);
  setPixel(255, 0, 0);  // Red on boot

  // 2. I2C
  Wire.begin(SDA_PIN, SCL_PIN);
  delay(100);

  // 3. AHT20
  if (aht.begin()) {
    ahtOk = true;
    Serial.println("AHT20 OK");
  } else {
    Serial.println("AHT20 NOT found");
  }

  // 4. BMP280
  if (bmp.begin(0x76)) {
    bmpOk = true;
    Serial.println("BMP280 OK (0x76)");
  } else if (bmp.begin(0x77)) {
    bmpOk = true;
    Serial.println("BMP280 OK (0x77)");
  } else {
    Serial.println("BMP280 NOT found");
  }
  if (bmpOk) {
    bmp.setSampling(Adafruit_BMP280::MODE_NORMAL,
                    Adafruit_BMP280::SAMPLING_X2,
                    Adafruit_BMP280::SAMPLING_X16,
                    Adafruit_BMP280::FILTER_X16,
                    Adafruit_BMP280::STANDBY_MS_500);
  }

  // 5. SPIFFS
  if (!SPIFFS.begin(true)) {
    Serial.println("SPIFFS mount failed");
  } else {
    Serial.println("SPIFFS mounted");
  }

  // 6. Load NVS config
  loadNvsConfig();

  // 7. Load cursor from NVS
  loadNvsCursor();
  Serial.printf("Cursor: file=%d cnt=%d\n", curFile, curCnt);

  // 8. WiFi
  setupWiFi();

  // 9. OTA + NTP
  if (wifiConnected) {
    setupOTA();
    String ntpSrv = cfgNtpServer.length() > 0 ? cfgNtpServer : "pool.ntp.org";
    configTime(0, 0, ntpSrv.c_str(), "time.nist.gov");
    Serial.println("NTP configured");
  }

  // 10. Web server
  setupWebServer();

  // 11. BLE
  setupBLE();

  // 12. LED based on data
  if (!anyDataFileExists()) {
    updateLedMode(LED_EMPTY);
  } else {
    if (curCnt >= RECORDS_PER_FILE) updateLedMode(LED_FULL);
    else if (curCnt > 0)            updateLedMode(LED_FILLING);
    else                            updateLedMode(LED_EMPTY);
  }

  setPixel(0, 0, 255);  // Blue briefly to indicate setup done
  delay(500);
  Serial.println("Setup complete");
}

// ═══════════════════════════════════════════════════════════════════════════
// Loop
// ═══════════════════════════════════════════════════════════════════════════
void loop() {
  webServer.handleClient();
  if (wifiConnected) ArduinoOTA.handle();
  handleLed();

  unsigned long now = millis();
  if (now - lastSampleMs >= 1000) {
    lastSampleMs = now;
    sampleAndStore();
  }
}
