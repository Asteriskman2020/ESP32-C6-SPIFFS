/*
 * ESP32-C6 SPIFFS Data Logger
 * Sensors: AHT20 (temp/humidity) + BMP280 (pressure/temp) on I2C SDA=GPIO20 SCL=GPIO18
 * NeoPixel on GPIO8
 * BLE + Web Server + OTA
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_AHTX0.h>
#include <Adafruit_BMP280.h>
#include <Adafruit_NeoPixel.h>
#include <SPIFFS.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoOTA.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ─── Pin definitions ────────────────────────────────────────────────
#define SDA_PIN       20
#define SCL_PIN       18
#define NEOPIXEL_PIN   8
#define NUM_PIXELS     1
#define PIXEL_BRIGHTNESS 50

// ─── SPIFFS ──────────────────────────────────────────────────────────
#define DATA_FILE     "/data.json"
#define MAX_SAMPLES   20

// ─── BLE UUIDs ───────────────────────────────────────────────────────
#define SERVICE_UUID  "bc000001-0000-1000-8000-00805f9b34fb"
#define CMD_UUID      "bc000002-0000-1000-8000-00805f9b34fb"
#define FILE_UUID     "bc000003-0000-1000-8000-00805f9b34fb"
#define STAT_UUID     "bc000004-0000-1000-8000-00805f9b34fb"

// ─── AP Fallback ─────────────────────────────────────────────────────
#define AP_SSID       "ESP32C6-SPIFFS"
#define AP_PASS       "12345678"

// ─── Data record ─────────────────────────────────────────────────────
struct SensorRecord {
  int   idx;
  float tempAht;
  float humidity;
  float pressure;
  float tempBmp;
};

SensorRecord buf[MAX_SAMPLES];
int sampleCount = 0;

// ─── Hardware objects ────────────────────────────────────────────────
Adafruit_AHTX0      aht;
Adafruit_BMP280     bmp;
Adafruit_NeoPixel   pixels(NUM_PIXELS, NEOPIXEL_PIN, NEO_GRB + NEO_KHZ800);
WebServer           server(80);
Preferences         prefs;

// ─── BLE objects ─────────────────────────────────────────────────────
BLEServer          *pServer     = nullptr;
BLECharacteristic  *pCmdChar   = nullptr;
BLECharacteristic  *pFileChar  = nullptr;
BLECharacteristic  *pStatChar  = nullptr;
bool bleConnected = false;
bool bleWasConnected = false;

// ─── WiFi config ─────────────────────────────────────────────────────
String cfgSsid, cfgPass, cfgMqttIp, cfgMqttPort, cfgMqttUser, cfgMqttPass;

// ─── Timing ──────────────────────────────────────────────────────────
unsigned long lastSensorMs  = 0;
unsigned long ledTimer      = 0;
bool          ledState      = false;

// ─── LED state machine ────────────────────────────────────────────────
enum LedMode { LED_FAST_RED, LED_QUICK_GREEN, LED_SLOW_GREEN, LED_CLEAR_RED };
LedMode ledMode = LED_FAST_RED;
unsigned long clearRedStart = 0;

// ─── HTML Pages (PROGMEM) ────────────────────────────────────────────
static const char PAGE_CONFIG[] PROGMEM = R"rawhtml(
<!DOCTYPE html><html><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>ESP32-C6 SPIFFS Logger</title>
<style>
body{font-family:sans-serif;background:#FFF3E0;margin:0;padding:16px}
h1{color:#F57C00;margin-bottom:4px}
.card{background:#FFE0B2;border-radius:8px;padding:16px;margin-bottom:16px;box-shadow:0 2px 4px rgba(0,0,0,.1)}
label{display:block;margin-top:8px;font-weight:bold;color:#E65100}
input{width:100%;box-sizing:border-box;padding:6px;border:1px solid #F57C00;border-radius:4px;margin-top:2px}
button,input[type=submit]{background:#F57C00;color:#fff;border:none;padding:10px 20px;border-radius:4px;cursor:pointer;font-size:1em;margin-top:10px}
button:hover,input[type=submit]:hover{background:#E65100}
a{color:#F57C00}
.stat{font-size:1.1em;margin:4px 0}
</style></head><body>
<h1>ESP32-C6 SPIFFS Logger</h1>
<div class='card'>
<div class='stat'><b>WiFi:</b> %WIFI_STATUS%</div>
<div class='stat'><b>Samples:</b> %SAMPLE_COUNT% / 20</div>
<div class='stat'><a href='/files'>Browse SPIFFS Files</a></div>
</div>
<div class='card'>
<h2 style='color:#F57C00;margin-top:0'>Settings</h2>
<form method='POST' action='/save'>
<label>WiFi SSID</label><input name='ssid' value='%SSID%'>
<label>WiFi Password</label><input name='pass' type='password' value='%PASS%'>
<label>MQTT IP</label><input name='mqttIp' value='%MQTT_IP%'>
<label>MQTT Port</label><input name='mqttPort' value='%MQTT_PORT%'>
<label>MQTT User</label><input name='mqttUser' value='%MQTT_USER%'>
<label>MQTT Password</label><input name='mqttPass' type='password' value='%MQTT_PASS%'>
<input type='submit' value='Save Settings'>
</form>
</div>
</body></html>
)rawhtml";

static const char PAGE_FILES[] PROGMEM = R"rawhtml(
<!DOCTYPE html><html><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>SPIFFS Files</title>
<style>
body{font-family:sans-serif;background:#FFF3E0;margin:0;padding:16px}
h1{color:#F57C00}
.card{background:#FFE0B2;border-radius:8px;padding:16px;margin-bottom:16px;box-shadow:0 2px 4px rgba(0,0,0,.1)}
table{width:100%;border-collapse:collapse}
th{background:#F57C00;color:#fff;padding:8px;text-align:left}
td{padding:8px;border-bottom:1px solid #FFD180}
a{color:#F57C00;text-decoration:none}
a:hover{text-decoration:underline}
.delbtn{background:#d32f2f;color:#fff;border:none;padding:4px 10px;border-radius:4px;cursor:pointer}
.delbtn:hover{background:#b71c1c}
</style></head><body>
<h1>SPIFFS Files</h1>
<a href='/'>&#8592; Back to Config</a>
<div class='card' style='margin-top:12px'>
<table><tr><th>Filename</th><th>Size</th><th>Download</th><th>Delete</th></tr>
%FILE_ROWS%
</table>
</div>
</body></html>
)rawhtml";

// ─── Forward declarations ─────────────────────────────────────────────
void loadFromSpiffs();
void saveToSpiffs();
void updateLed();
void handleRoot();
void handleSave();
void handleFiles();
void handleDownload();
void handleDelete();
void sendFileViaBle();
void statNotify(const String &msg);

// ═══════════════════════════════════════════════════════════════════════
// BLE Server Callbacks
// ═══════════════════════════════════════════════════════════════════════
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    bleConnected = true;
    Serial.println("[BLE] Client connected");
  }
  void onDisconnect(BLEServer *s) override {
    bleConnected = false;
    Serial.println("[BLE] Client disconnected – restarting advertising");
    BLEDevice::startAdvertising();
  }
};

class CmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String val = c->getValue().c_str();
    val.trim();
    Serial.printf("[BLE] CMD received: %s\n", val.c_str());
    if (val == "READFILE") {
      sendFileViaBle();
    } else if (val == "CLEARFILE") {
      SPIFFS.remove(DATA_FILE);
      sampleCount = 0;
      memset(buf, 0, sizeof(buf));
      ledMode      = LED_CLEAR_RED;
      clearRedStart = millis();
      statNotify("CLEARED");
      Serial.println("[BLE] File cleared");
    }
  }
};

// ═══════════════════════════════════════════════════════════════════════
// SPIFFS helpers
// ═══════════════════════════════════════════════════════════════════════
float extractField(const String &obj, const String &key) {
  int pos = obj.indexOf(key);
  if (pos < 0) return 0.0f;
  pos += key.length();
  int end = obj.indexOf(',', pos);
  if (end < 0) end = obj.indexOf('}', pos);
  if (end < 0) end = obj.length();
  return obj.substring(pos, end).toFloat();
}

void loadFromSpiffs() {
  if (!SPIFFS.exists(DATA_FILE)) {
    Serial.println("[SPIFFS] No data file found");
    return;
  }
  File f = SPIFFS.open(DATA_FILE, "r");
  if (!f) return;

  sampleCount = 0;
  String objBuf = "";
  bool inObj = false;
  int depth = 0;

  while (f.available() && sampleCount < MAX_SAMPLES) {
    char c = (char)f.read();
    if (!inObj) {
      if (c == '{') { inObj = true; depth = 1; objBuf = "{"; }
    } else {
      objBuf += c;
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          // parse this object
          SensorRecord r;
          r.idx      = (int)extractField(objBuf, "\"i\":");
          r.tempAht  = extractField(objBuf, "\"t\":");
          r.humidity = extractField(objBuf, "\"h\":");
          r.pressure = extractField(objBuf, "\"p\":");
          r.tempBmp  = extractField(objBuf, "\"tb\":");
          buf[sampleCount++] = r;
          inObj  = false;
          objBuf = "";
        }
      }
    }
  }
  f.close();
  Serial.printf("[SPIFFS] Loaded %d samples\n", sampleCount);
}

void saveToSpiffs() {
  File f = SPIFFS.open(DATA_FILE, "w");
  if (!f) { Serial.println("[SPIFFS] Failed to open for write"); return; }
  f.print("[");
  for (int i = 0; i < sampleCount; i++) {
    char entry[120];
    snprintf(entry, sizeof(entry),
      "{\"i\":%d,\"t\":%.1f,\"h\":%.1f,\"p\":%.1f,\"tb\":%.1f}",
      buf[i].idx, buf[i].tempAht, buf[i].humidity, buf[i].pressure, buf[i].tempBmp);
    f.print(entry);
    if (i < sampleCount - 1) f.print(",");
  }
  f.print("]");
  f.close();
}

// ═══════════════════════════════════════════════════════════════════════
// BLE file streaming
// ═══════════════════════════════════════════════════════════════════════
void statNotify(const String &msg) {
  if (pStatChar && bleConnected) {
    pStatChar->setValue(msg.c_str());
    pStatChar->notify();
  }
}

void sendFileViaBle() {
  if (!pFileChar || !bleConnected) return;
  if (!SPIFFS.exists(DATA_FILE)) {
    pFileChar->setValue("EMPTY");
    pFileChar->notify();
    delay(50);
    pFileChar->setValue("END");
    pFileChar->notify();
    return;
  }
  File f = SPIFFS.open(DATA_FILE, "r");
  if (!f || f.size() == 0) {
    if (f) f.close();
    pFileChar->setValue("EMPTY");
    pFileChar->notify();
    delay(50);
    pFileChar->setValue("END");
    pFileChar->notify();
    return;
  }
  const int CHUNK = 200;
  char chunk[CHUNK + 1];
  while (f.available()) {
    int n = f.readBytes(chunk, CHUNK);
    chunk[n] = '\0';
    pFileChar->setValue(chunk);
    pFileChar->notify();
    delay(80);
  }
  f.close();
  pFileChar->setValue("END");
  pFileChar->notify();
  statNotify("READY");
  Serial.println("[BLE] File sent");
}

// ═══════════════════════════════════════════════════════════════════════
// LED
// ═══════════════════════════════════════════════════════════════════════
void updateLed() {
  unsigned long now = millis();
  unsigned long half = 1000;

  if (ledMode == LED_CLEAR_RED) {
    half = 2000;
    if (now - ledTimer >= half) {
      ledState = !ledState;
      ledTimer = now;
    }
    if (now - clearRedStart >= 6000) {
      ledMode = LED_FAST_RED;
    }
  } else if (sampleCount == 0) {
    ledMode = LED_FAST_RED;
    half = 100;
    if (now - ledTimer >= half) { ledState = !ledState; ledTimer = now; }
  } else if (sampleCount < MAX_SAMPLES) {
    ledMode = LED_QUICK_GREEN;
    half = 200;
    if (now - ledTimer >= half) { ledState = !ledState; ledTimer = now; }
  } else {
    ledMode = LED_SLOW_GREEN;
    half = 1000;
    if (now - ledTimer >= half) { ledState = !ledState; ledTimer = now; }
  }

  uint32_t color = 0;
  if (ledState) {
    if (ledMode == LED_FAST_RED || ledMode == LED_CLEAR_RED)
      color = pixels.Color(PIXEL_BRIGHTNESS, 0, 0);
    else
      color = pixels.Color(0, PIXEL_BRIGHTNESS, 0);
  }
  pixels.setPixelColor(0, color);
  pixels.show();
}

// ═══════════════════════════════════════════════════════════════════════
// Web Server handlers
// ═══════════════════════════════════════════════════════════════════════
void handleRoot() {
  String page = FPSTR(PAGE_CONFIG);
  String wifiStat = WiFi.isConnected() ?
    ("Connected: " + WiFi.localIP().toString()) : "Not connected (AP mode)";
  page.replace("%WIFI_STATUS%",  wifiStat);
  page.replace("%SAMPLE_COUNT%", String(sampleCount));
  page.replace("%SSID%",         cfgSsid);
  page.replace("%PASS%",         cfgPass);
  page.replace("%MQTT_IP%",      cfgMqttIp);
  page.replace("%MQTT_PORT%",    cfgMqttPort);
  page.replace("%MQTT_USER%",    cfgMqttUser);
  page.replace("%MQTT_PASS%",    cfgMqttPass);
  server.send(200, "text/html", page);
}

void handleSave() {
  if (server.hasArg("ssid"))     { cfgSsid     = server.arg("ssid");     prefs.putString("ssid",     cfgSsid); }
  if (server.hasArg("pass"))     { cfgPass     = server.arg("pass");     prefs.putString("pass",     cfgPass); }
  if (server.hasArg("mqttIp"))   { cfgMqttIp   = server.arg("mqttIp");   prefs.putString("mqttIp",   cfgMqttIp); }
  if (server.hasArg("mqttPort")) { cfgMqttPort = server.arg("mqttPort"); prefs.putString("mqttPort", cfgMqttPort); }
  if (server.hasArg("mqttUser")) { cfgMqttUser = server.arg("mqttUser"); prefs.putString("mqttUser", cfgMqttUser);  }
  if (server.hasArg("mqttPass")) { cfgMqttPass = server.arg("mqttPass"); prefs.putString("mqttPass", cfgMqttPass); }
  server.sendHeader("Location", "/");
  server.send(302, "text/plain", "Saved");
  Serial.println("[CFG] Settings saved");
}

void handleFiles() {
  String rows = "";
  File root = SPIFFS.open("/");
  File file = root.openNextFile();
  while (file) {
    String fname = String(file.name());
    if (!fname.startsWith("/")) fname = "/" + fname;
    size_t sz = file.size();
    rows += "<tr><td>" + fname + "</td><td>" + String(sz) + " B</td>";
    rows += "<td><a href='/download?f=" + fname + "'>Download</a></td>";
    rows += "<td><form method='POST' action='/delete' style='margin:0'>"
            "<input type='hidden' name='f' value='" + fname + "'>"
            "<button class='delbtn' type='submit'>Delete</button></form></td></tr>";
    file = root.openNextFile();
  }
  if (rows.isEmpty()) rows = "<tr><td colspan='4' style='text-align:center'>No files</td></tr>";
  String page = FPSTR(PAGE_FILES);
  page.replace("%FILE_ROWS%", rows);
  server.send(200, "text/html", page);
}

void handleDownload() {
  if (!server.hasArg("f")) { server.send(400, "text/plain", "Missing f"); return; }
  String fname = server.arg("f");
  if (!SPIFFS.exists(fname)) { server.send(404, "text/plain", "Not found"); return; }
  File f = SPIFFS.open(fname, "r");
  String disp = "attachment; filename=\"" + fname.substring(1) + "\"";
  server.sendHeader("Content-Disposition", disp);
  server.streamFile(f, "application/octet-stream");
  f.close();
}

void handleDelete() {
  if (!server.hasArg("f")) { server.send(400, "text/plain", "Missing f"); return; }
  String fname = server.arg("f");
  if (SPIFFS.exists(fname)) {
    SPIFFS.remove(fname);
    if (fname == DATA_FILE) {
      sampleCount = 0;
      memset(buf, 0, sizeof(buf));
      ledMode = LED_CLEAR_RED;
      clearRedStart = millis();
    }
  }
  server.sendHeader("Location", "/files");
  server.send(302, "text/plain", "Deleted");
}

// ═══════════════════════════════════════════════════════════════════════
// setup()
// ═══════════════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n[BOOT] ESP32-C6 SPIFFS Logger");

  // NeoPixel
  pixels.begin();
  pixels.setBrightness(PIXEL_BRIGHTNESS);
  pixels.clear();
  pixels.show();

  // I2C
  Wire.begin(SDA_PIN, SCL_PIN);

  // AHT20
  if (!aht.begin()) {
    Serial.println("[WARN] AHT20 not found – using dummy values");
  }

  // BMP280 (0x76 default)
  if (!bmp.begin(0x76)) {
    if (!bmp.begin(0x77)) {
      Serial.println("[WARN] BMP280 not found – using dummy values");
    }
  }

  // SPIFFS
  if (!SPIFFS.begin(true)) {
    Serial.println("[ERROR] SPIFFS mount failed");
  } else {
    Serial.println("[SPIFFS] Mounted OK");
    loadFromSpiffs();
  }

  // Preferences
  prefs.begin("cfg", false);
  cfgSsid     = prefs.getString("ssid",     "");
  cfgPass     = prefs.getString("pass",     "");
  cfgMqttIp   = prefs.getString("mqttIp",   "");
  cfgMqttPort = prefs.getString("mqttPort", "1883");
  cfgMqttUser = prefs.getString("mqttUser", "");
  cfgMqttPass = prefs.getString("mqttPass", "");

  // WiFi
  bool wifiOk = false;
  if (cfgSsid.length() > 0) {
    Serial.printf("[WiFi] Connecting to %s ...\n", cfgSsid.c_str());
    WiFi.mode(WIFI_STA);
    WiFi.begin(cfgSsid.c_str(), cfgPass.c_str());
    unsigned long t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 10000) delay(200);
    if (WiFi.status() == WL_CONNECTED) {
      wifiOk = true;
      Serial.printf("[WiFi] Connected: %s\n", WiFi.localIP().toString().c_str());
    } else {
      Serial.println("[WiFi] Failed – starting AP");
    }
  }
  if (!wifiOk) {
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("[WiFi] AP started: %s\n", AP_SSID);
  }

  // OTA
  if (wifiOk) {
    ArduinoOTA.setHostname("esp32c6-spiffs");
    ArduinoOTA.onStart([]() { Serial.println("[OTA] Start"); });
    ArduinoOTA.onEnd([]()   { Serial.println("[OTA] End"); });
    ArduinoOTA.onError([](ota_error_t e) { Serial.printf("[OTA] Error %u\n", e); });
    ArduinoOTA.begin();
    Serial.println("[OTA] Ready");
  }

  // Web server
  server.on("/",        HTTP_GET,  handleRoot);
  server.on("/save",    HTTP_POST, handleSave);
  server.on("/files",   HTTP_GET,  handleFiles);
  server.on("/download",HTTP_GET,  handleDownload);
  server.on("/delete",  HTTP_POST, handleDelete);
  server.begin();
  Serial.println("[HTTP] Server started on port 80");

  // BLE
  BLEDevice::init("ESP32-C6 SPIFFS");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCmdChar = pService->createCharacteristic(
    CMD_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  pCmdChar->setCallbacks(new CmdCallbacks());

  pFileChar = pService->createCharacteristic(
    FILE_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pFileChar->addDescriptor(new BLE2902());

  pStatChar = pService->createCharacteristic(
    STAT_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pStatChar->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising *pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] Advertising started");

  Serial.printf("[BOOT] Boot complete. Samples loaded: %d\n", sampleCount);
}

// ═══════════════════════════════════════════════════════════════════════
// loop()
// ═══════════════════════════════════════════════════════════════════════
void loop() {
  server.handleClient();
  ArduinoOTA.handle();
  updateLed();

  unsigned long now = millis();
  if (now - lastSensorMs >= 1000) {
    lastSensorMs = now;

    if (sampleCount < MAX_SAMPLES) {
      float tAht = 0, hum = 0, tBmp = 0, pres = 0;

      sensors_event_t humEv, tempEv;
      if (aht.getEvent(&humEv, &tempEv)) {
        tAht = tempEv.temperature;
        hum  = humEv.relative_humidity;
      }
      tBmp = bmp.readTemperature();
      pres = bmp.readPressure() / 100.0f;

      buf[sampleCount].idx      = sampleCount + 1;
      buf[sampleCount].tempAht  = tAht;
      buf[sampleCount].humidity = hum;
      buf[sampleCount].pressure = pres;
      buf[sampleCount].tempBmp  = tBmp;
      sampleCount++;

      Serial.printf("[DATA] #%d  T=%.1f°C  H=%.1f%%  P=%.1fhPa  Tb=%.1f°C\n",
        sampleCount, tAht, hum, pres, tBmp);

      saveToSpiffs();

      if (sampleCount >= MAX_SAMPLES) {
        Serial.println("[DATA] Buffer FULL – 20 samples stored");
      }
    }
  }
}
