/*
 * ESP32_V1.ino
 * ESP32-C6 Super Mini - AHT20 + BMP280 sensors, WS2812B LED, SPIFFS CSV logging
 * BLE, WiFi, WebServer, OTA, NTP, NVS
 */

#include <Wire.h>
#include <Adafruit_AHTX0.h>
#include <Adafruit_BMP280.h>
#include <Adafruit_NeoPixel.h>
#include <SPIFFS.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoOTA.h>
#include <Preferences.h>
#include <NimBLEDevice.h>
#include <time.h>

// ─── Pins ───────────────────────────────────────────────────────────────────
#define SDA_PIN       20
#define SCL_PIN       18
#define NEOPIXEL_PIN  8
#define NUM_PIXELS    1

// ─── SPIFFS / CSV ────────────────────────────────────────────────────────────
#define MAX_RECORDS_PER_FILE  20
#define MAX_FILES             5
#define CSV_HEADER            "index,timestamp,date_time,temp_aht,humidity,pressure,temp_bmp\n"

// ─── BLE UUIDs ──────────────────────────────────────────────────────────────
#define SERVICE_UUID  "cc000001-0000-1000-8000-00805f9b34fb"
#define CMD_UUID      "cc000002-0000-1000-8000-00805f9b34fb"
#define FILE_UUID     "cc000003-0000-1000-8000-00805f9b34fb"
#define STAT_UUID     "cc000004-0000-1000-8000-00805f9b34fb"

// ─── Objects ─────────────────────────────────────────────────────────────────
Adafruit_AHTX0       aht;
Adafruit_BMP280      bmp;
Adafruit_NeoPixel    pixel(NUM_PIXELS, NEOPIXEL_PIN, NEO_GRB + NEO_KHZ800);
Preferences          prefs;
WebServer            server(80);

// ─── BLE ─────────────────────────────────────────────────────────────────────
NimBLEServer*         pServer   = nullptr;
NimBLECharacteristic* pFileChar = nullptr;
NimBLECharacteristic* pStatChar = nullptr;
bool bleConnected = false;

// ─── Sensor data ─────────────────────────────────────────────────────────────
float g_tempAht  = 0.0f;
float g_humidity = 0.0f;
float g_pressure = 0.0f;
float g_tempBmp  = 0.0f;

// ─── State ───────────────────────────────────────────────────────────────────
bool  wifiConnected = false;
bool  ntpSynced     = false;
bool  fsReady       = false;
bool  apMode        = false;

// NVS-backed vars
int   slotIdx   = 0;   // 0-4
int   recCnt    = 0;   // 0-19
String curFile  = "";

// ─── LED / timing ────────────────────────────────────────────────────────────
unsigned long lastSensorMs  = 0;
unsigned long ledFlashMs    = 0;
bool          ledState      = false;
bool          blueSolidMode = false;
unsigned long blueSolidStart = 0;

// ─── BLE command queue ───────────────────────────────────────────────────────
String pendingBleCmd = "";

// ─── WiFi credentials ───────────────────────────────────────────────────────
String cfg_ssid      = "";
String cfg_pass      = "";
String cfg_mqttIp    = "";
String cfg_mqttUser  = "";
String cfg_mqttPass  = "";
String cfg_mqttPort  = "1883";
int    cfg_tzOffset  = 7;   // UTC+7

// ─── NVS keys ────────────────────────────────────────────────────────────────
#define NVS_SLOT    "slotIdx"
#define NVS_REC     "recCnt"
#define NVS_FILE    "curFile"

// ─────────────────────────────────────────────────────────────────────────────
//  Color helpers
// ─────────────────────────────────────────────────────────────────────────────
struct RGBColor { uint8_t r, g, b; };

RGBColor slotColor(int slot) {
    switch (slot) {
        case 0: return {255, 0, 0};       // RED
        case 1: return {255, 128, 0};     // ORANGE
        case 2: return {255, 255, 0};     // YELLOW
        case 3: return {0, 255, 0};       // GREEN
        case 4: return {128, 0, 255};     // PURPLE
        default: return {255, 0, 0};
    }
}

void setPixelColor(uint8_t r, uint8_t g, uint8_t b) {
    pixel.setPixelColor(0, pixel.Color(r, g, b));
    pixel.show();
}

void ledOff() {
    pixel.setPixelColor(0, 0);
    pixel.show();
}

// ─────────────────────────────────────────────────────────────────────────────
//  NVS helpers
// ─────────────────────────────────────────────────────────────────────────────
void loadNvs() {
    prefs.begin("v1data", false);
    slotIdx = prefs.getInt(NVS_SLOT, 0);
    recCnt  = prefs.getInt(NVS_REC, 0);
    curFile = prefs.getString(NVS_FILE, "");
    prefs.end();
}

void saveNvs() {
    prefs.begin("v1data", false);
    prefs.putInt(NVS_SLOT, slotIdx);
    prefs.putInt(NVS_REC, recCnt);
    prefs.putString(NVS_FILE, curFile);
    prefs.end();
}

void loadWifiConfig() {
    prefs.begin("wificfg", true);
    cfg_ssid     = prefs.getString("ssid", "");
    cfg_pass     = prefs.getString("pass", "");
    cfg_mqttIp   = prefs.getString("mqttIp", "");
    cfg_mqttUser = prefs.getString("mqttUser", "");
    cfg_mqttPass = prefs.getString("mqttPass", "");
    cfg_mqttPort = prefs.getString("mqttPort", "1883");
    cfg_tzOffset = prefs.getInt("tzOffset", 7);
    prefs.end();
}

void saveWifiConfig() {
    prefs.begin("wificfg", false);
    prefs.putString("ssid",     cfg_ssid);
    prefs.putString("pass",     cfg_pass);
    prefs.putString("mqttIp",   cfg_mqttIp);
    prefs.putString("mqttUser", cfg_mqttUser);
    prefs.putString("mqttPass", cfg_mqttPass);
    prefs.putString("mqttPort", cfg_mqttPort);
    prefs.putInt("tzOffset",    cfg_tzOffset);
    prefs.end();
}

// ─────────────────────────────────────────────────────────────────────────────
//  Time helpers
// ─────────────────────────────────────────────────────────────────────────────
String getFormattedDateTime() {
    struct tm ti;
    if (!getLocalTime(&ti)) return "01-01-2024 00-00-00";
    char buf[32];
    snprintf(buf, sizeof(buf), "%02d-%02d-%04d %02d:%02d:%02d",
             ti.tm_mday, ti.tm_mon+1, ti.tm_year+1900,
             ti.tm_hour, ti.tm_min, ti.tm_sec);
    return String(buf);
}

String getFilenameDateTime() {
    // "DD-MM-YYYY_HH-MM-SS.csv"
    struct tm ti;
    if (!getLocalTime(&ti)) return "01-01-2024_00-00-00.csv";
    char buf[32];
    snprintf(buf, sizeof(buf), "%02d-%02d-%04d_%02d-%02d-%02d.csv",
             ti.tm_mday, ti.tm_mon+1, ti.tm_year+1900,
             ti.tm_hour, ti.tm_min, ti.tm_sec);
    return String(buf);
}

time_t getUnixTimestamp() {
    struct tm ti;
    if (!getLocalTime(&ti)) return 0;
    return mktime(&ti);
}

// ─────────────────────────────────────────────────────────────────────────────
//  SPIFFS file management
// ─────────────────────────────────────────────────────────────────────────────
std::vector<String> listCsvFiles() {
    std::vector<String> files;
    File root = SPIFFS.open("/");
    if (!root || !root.isDirectory()) return files;
    File f = root.openNextFile();
    while (f) {
        String name = String(f.name());
        if (name.endsWith(".csv")) {
            // Strip leading slash
            if (name.startsWith("/")) name = name.substring(1);
            files.push_back(name);
        }
        f = root.openNextFile();
    }
    return files;
}

void deleteOldestFile() {
    auto files = listCsvFiles();
    if (files.empty()) return;
    // Sort by name (filenames are date-based, lexicographic = oldest first)
    std::sort(files.begin(), files.end());
    String oldest = "/" + files[0];
    SPIFFS.remove(oldest);
    Serial.println("Deleted oldest: " + files[0]);
}

void createNewFile() {
    // Enforce max 5 files
    auto files = listCsvFiles();
    while ((int)files.size() >= MAX_FILES) {
        deleteOldestFile();
        files = listCsvFiles();
    }
    curFile = getFilenameDateTime();
    String path = "/" + curFile;
    File f = SPIFFS.open(path, FILE_WRITE);
    if (f) {
        f.print(CSV_HEADER);
        f.close();
        Serial.println("Created file: " + curFile);
    } else {
        Serial.println("Failed to create file: " + curFile);
    }
    saveNvs();
}

void appendRecord(float tAht, float hum, float pres, float tBmp) {
    if (curFile.length() == 0) return;
    String path = "/" + curFile;
    File f = SPIFFS.open(path, FILE_APPEND);
    if (!f) {
        Serial.println("Failed to open for append: " + curFile);
        return;
    }
    time_t ts = getUnixTimestamp();
    String dt = getFormattedDateTime();
    // Replace ':' in dt with ':' (already correct format for CSV)
    String line = String(recCnt + 1) + "," +
                  String((long)ts) + "," +
                  dt + "," +
                  String(tAht, 2) + "," +
                  String(hum, 2) + "," +
                  String(pres, 2) + "," +
                  String(tBmp, 2) + "\n";
    f.print(line);
    f.close();
    recCnt++;
    saveNvs();
    Serial.println("Wrote record " + String(recCnt) + " to " + curFile);
}

int countRecordsInFile(const String& filename) {
    String path = "/" + filename;
    File f = SPIFFS.open(path, FILE_READ);
    if (!f) return 0;
    int count = 0;
    bool firstLine = true;
    while (f.available()) {
        String line = f.readStringUntil('\n');
        if (firstLine) { firstLine = false; continue; } // skip header
        if (line.length() > 3) count++;
    }
    f.close();
    return count;
}

// ─────────────────────────────────────────────────────────────────────────────
//  LED update (non-blocking)
// ─────────────────────────────────────────────────────────────────────────────
void updateLed() {
    if (blueSolidMode) {
        // Blue solid for 5 seconds
        setPixelColor(0, 0, 255);
        if (millis() - blueSolidStart >= 5000) {
            blueSolidMode = false;
            ledOff();
        }
        return;
    }
    if (!fsReady || !ntpSynced) {
        ledOff();
        return;
    }
    // Fast flash based on current slot
    unsigned long now = millis();
    if (now - ledFlashMs >= 100) {
        ledFlashMs = now;
        ledState = !ledState;
        if (ledState) {
            RGBColor c = slotColor(slotIdx);
            setPixelColor(c.r, c.g, c.b);
        } else {
            ledOff();
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BLE Server Callbacks
// ─────────────────────────────────────────────────────────────────────────────
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pSvr, NimBLEConnInfo& connInfo) override {
        bleConnected = true;
        Serial.println("BLE connected");
    }
    void onDisconnect(NimBLEServer* pSvr, NimBLEConnInfo& connInfo, int reason) override {
        bleConnected = false;
        Serial.println("BLE disconnected");
        pSvr->startAdvertising();
    }
};

class CmdCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        std::string val = pChar->getValue();
        pendingBleCmd = String(val.c_str());
        Serial.println("BLE CMD: " + pendingBleCmd);
    }
};

// Send data via BLE in 200-byte chunks
void bleSendChunked(NimBLECharacteristic* pChar, const String& data) {
    int len = data.length();
    int offset = 0;
    while (offset < len) {
        int chunkLen = min(200, len - offset);
        String chunk = data.substring(offset, offset + chunkLen);
        pChar->setValue(chunk.c_str());
        pChar->notify();
        delay(20);
        offset += chunkLen;
    }
    // Send END
    pChar->setValue("END");
    pChar->notify();
}

// ─────────────────────────────────────────────────────────────────────────────
//  Process BLE command
// ─────────────────────────────────────────────────────────────────────────────
void processBleCommand(const String& cmd) {
    if (cmd == "LISTFILES") {
        auto files = listCsvFiles();
        String json = "[";
        for (int i = 0; i < (int)files.size(); i++) {
            if (i > 0) json += ",";
            int sz = 0;
            File f = SPIFFS.open("/" + files[i]);
            if (f) { sz = f.size(); f.close(); }
            int recs = countRecordsInFile(files[i]);
            json += "{\"name\":\"" + files[i] + "\",\"size\":" + String(sz) +
                    ",\"records\":" + String(recs) + "}";
        }
        json += "]";
        bleSendChunked(pFileChar, json);
    }
    else if (cmd.startsWith("READFILE:")) {
        String fname = cmd.substring(9);
        File f = SPIFFS.open("/" + fname);
        if (!f) {
            pFileChar->setValue("ERROR:NOT_FOUND");
            pFileChar->notify();
            pFileChar->setValue("END");
            pFileChar->notify();
            return;
        }
        String content = "";
        while (f.available()) {
            content += (char)f.read();
        }
        f.close();
        bleSendChunked(pFileChar, content);
    }
    else if (cmd.startsWith("CLEARFILE:")) {
        String fname = cmd.substring(10);
        bool ok = SPIFFS.remove("/" + fname);
        String resp = ok ? ("CLEARED:" + fname) : ("ERROR:" + fname);
        pStatChar->setValue(resp.c_str());
        pStatChar->notify();
        // If we deleted current file, reset
        if (fname == curFile) {
            curFile = "";
            recCnt = 0;
            saveNvs();
        }
    }
    else if (cmd == "CLEARALL") {
        auto files = listCsvFiles();
        for (auto& f : files) SPIFFS.remove("/" + f);
        curFile = "";
        recCnt  = 0;
        slotIdx = 0;
        saveNvs();
        pStatChar->setValue("CLEARED:ALL");
        pStatChar->notify();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Web server HTML helpers
// ─────────────────────────────────────────────────────────────────────────────
String htmlHeader(const String& title) {
    return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
           "<meta name='viewport' content='width=device-width, initial-scale=1'>"
           "<title>" + title + "</title>"
           "<style>"
           "body{font-family:Arial,sans-serif;background:#E1F5FE;margin:0;padding:0;}"
           ".container{max-width:800px;margin:20px auto;padding:0 12px;}"
           "h1{background:#039BE5;color:#fff;padding:16px 20px;margin:0;font-size:20px;}"
           "h2{background:#039BE5;color:#fff;padding:8px 14px;font-size:15px;margin:0 0 10px 0;border-radius:4px 4px 0 0;}"
           ".card{background:#fff;border-radius:8px;margin-bottom:16px;box-shadow:0 2px 4px rgba(0,0,0,0.1);overflow:hidden;}"
           ".card-body{padding:14px;}"
           "table{width:100%;border-collapse:collapse;}"
           "th{background:#E3F2FD;padding:6px;text-align:left;font-size:12px;color:#039BE5;}"
           "td{padding:6px;font-size:12px;border-bottom:1px solid #f0f0f0;}"
           ".btn{display:inline-block;padding:6px 12px;border-radius:4px;text-decoration:none;font-size:12px;cursor:pointer;border:none;}"
           ".btn-blue{background:#039BE5;color:#fff;}"
           ".btn-red{background:#E53935;color:#fff;}"
           ".btn-orange{background:#FF6F00;color:#fff;}"
           "input,select{padding:8px;width:100%;box-sizing:border-box;margin-bottom:8px;border:1px solid #ccc;border-radius:4px;}"
           ".label{font-size:13px;color:#555;margin-bottom:2px;}"
           ".status-ok{color:#43A047;font-weight:bold;}"
           ".status-bad{color:#E53935;}"
           "</style></head><body>"
           "<h1>ESP32-V1 Data Logger</h1>"
           "<div class='container'>";
}

String htmlFooter() {
    return "</div></body></html>";
}

// ─────────────────────────────────────────────────────────────────────────────
//  Web routes
// ─────────────────────────────────────────────────────────────────────────────
void handleRoot() {
    String html = htmlHeader("ESP32-V1");

    // Status card
    html += "<div class='card'><h2>Device Status</h2><div class='card-body'>";
    html += "<table>";
    html += "<tr><th>WiFi</th><td class='" + String(wifiConnected ? "status-ok" : "status-bad") + "'>" +
            (wifiConnected ? ("Connected: " + WiFi.SSID()) : (apMode ? "AP Mode" : "Disconnected")) + "</td></tr>";
    html += "<tr><th>NTP</th><td class='" + String(ntpSynced ? "status-ok" : "status-bad") + "'>" +
            (ntpSynced ? ("Synced - " + getFormattedDateTime()) : "Not synced") + "</td></tr>";
    html += "<tr><th>SPIFFS</th><td class='" + String(fsReady ? "status-ok" : "status-bad") + "'>" +
            (fsReady ? "OK" : "Error") + "</td></tr>";
    html += "<tr><th>BLE</th><td>" + String(bleConnected ? "Connected" : "Advertising") + "</td></tr>";
    html += "<tr><th>Current File</th><td>" + curFile + "</td></tr>";
    html += "<tr><th>Records</th><td>" + String(recCnt) + " / " + String(MAX_RECORDS_PER_FILE) + "</td></tr>";
    html += "<tr><th>Slot</th><td>" + String(slotIdx) + " / " + String(MAX_FILES-1) + "</td></tr>";
    if (fsReady) {
        html += "<tr><th>SPIFFS Used</th><td>" + String(SPIFFS.usedBytes()) + " / " + String(SPIFFS.totalBytes()) + " bytes</td></tr>";
    }
    html += "</table></div></div>";

    // Files card
    html += "<div class='card'><h2>CSV Files</h2><div class='card-body'>";
    if (fsReady) {
        auto files = listCsvFiles();
        if (files.empty()) {
            html += "<p style='color:#757575'>No files yet.</p>";
        } else {
            html += "<table><tr><th>Filename</th><th>Size</th><th>Records</th><th>Actions</th></tr>";
            for (auto& fn : files) {
                File f = SPIFFS.open("/" + fn);
                int sz = f ? f.size() : 0;
                if (f) f.close();
                int recs = countRecordsInFile(fn);
                html += "<tr><td>" + fn + "</td><td>" + String(sz) + "B</td><td>" + String(recs) + "</td>";
                html += "<td><a href='/download?f=" + fn + "' class='btn btn-blue'>Download</a> ";
                html += "<a href='/delete?f=" + fn + "' class='btn btn-red' onclick=\"return confirm('Delete " + fn + "?')\">Delete</a></td></tr>";
            }
            html += "</table>";
        }
    } else {
        html += "<p class='status-bad'>SPIFFS not ready</p>";
    }
    html += "<br><a href='/config' class='btn btn-orange'>WiFi / MQTT Config</a></div></div>";

    html += htmlFooter();
    server.send(200, "text/html", html);
}

void handleConfig() {
    String html = htmlHeader("Config");
    html += "<div class='card'><h2>WiFi & MQTT Configuration</h2><div class='card-body'>";
    html += "<form action='/saveconfig' method='POST'>";
    html += "<div class='label'>WiFi SSID</div><input name='ssid' value='" + cfg_ssid + "'>";
    html += "<div class='label'>WiFi Password</div><input name='pass' type='password' value='" + cfg_pass + "'>";
    html += "<div class='label'>MQTT IP</div><input name='mqttIp' value='" + cfg_mqttIp + "'>";
    html += "<div class='label'>MQTT User</div><input name='mqttUser' value='" + cfg_mqttUser + "'>";
    html += "<div class='label'>MQTT Password</div><input name='mqttPass' type='password' value='" + cfg_mqttPass + "'>";
    html += "<div class='label'>MQTT Port</div><input name='mqttPort' value='" + cfg_mqttPort + "'>";
    html += "<div class='label'>Timezone Offset (hours, e.g. 7 for UTC+7)</div><input name='tzOffset' type='number' value='" + String(cfg_tzOffset) + "'>";
    html += "<br><input type='submit' class='btn btn-blue' value='Save &amp; Restart'>";
    html += "</form></div></div>";
    html += htmlFooter();
    server.send(200, "text/html", html);
}

void handleSaveConfig() {
    if (server.hasArg("ssid"))     cfg_ssid     = server.arg("ssid");
    if (server.hasArg("pass"))     cfg_pass     = server.arg("pass");
    if (server.hasArg("mqttIp"))   cfg_mqttIp   = server.arg("mqttIp");
    if (server.hasArg("mqttUser")) cfg_mqttUser = server.arg("mqttUser");
    if (server.hasArg("mqttPass")) cfg_mqttPass = server.arg("mqttPass");
    if (server.hasArg("mqttPort")) cfg_mqttPort = server.arg("mqttPort");
    if (server.hasArg("tzOffset")) cfg_tzOffset = server.arg("tzOffset").toInt();
    saveWifiConfig();
    server.send(200, "text/html",
        htmlHeader("Saved") +
        "<div class='card'><div class='card-body'>"
        "<p class='status-ok'>Saved! Restarting...</p>"
        "</div></div>" + htmlFooter());
    delay(2000);
    ESP.restart();
}

void handleDownload() {
    if (!server.hasArg("f")) { server.send(400, "text/plain", "Missing f param"); return; }
    String fname = server.arg("f");
    String path = "/" + fname;
    if (!SPIFFS.exists(path)) { server.send(404, "text/plain", "Not found"); return; }
    File f = SPIFFS.open(path, FILE_READ);
    if (!f) { server.send(500, "text/plain", "Open failed"); return; }
    server.sendHeader("Content-Disposition", "attachment; filename=" + fname);
    server.streamFile(f, "text/csv");
    f.close();
}

void handleDelete() {
    if (!server.hasArg("f")) { server.send(400, "text/plain", "Missing f"); return; }
    String fname = server.arg("f");
    SPIFFS.remove("/" + fname);
    if (fname == curFile) { curFile = ""; recCnt = 0; saveNvs(); }
    server.sendHeader("Location", "/");
    server.send(302, "text/plain", "");
}

void handleApiStatus() {
    String json = "{";
    json += "\"wifi\":" + String(wifiConnected ? "true" : "false") + ",";
    json += "\"ntp\":" + String(ntpSynced ? "true" : "false") + ",";
    json += "\"ble_connected\":" + String(bleConnected ? "true" : "false") + ",";
    json += "\"slot\":" + String(slotIdx) + ",";
    json += "\"record_count\":" + String(recCnt) + ",";
    json += "\"spiffs_used\":" + String(fsReady ? (long)SPIFFS.usedBytes() : 0L) + ",";
    json += "\"spiffs_total\":" + String(fsReady ? (long)SPIFFS.totalBytes() : 0L) + ",";
    json += "\"files\":[";
    auto files = listCsvFiles();
    for (int i = 0; i < (int)files.size(); i++) {
        if (i > 0) json += ",";
        json += "\"" + files[i] + "\"";
    }
    json += "]}";
    server.send(200, "application/json", json);
}

void handleApiFiles() {
    auto files = listCsvFiles();
    String json = "[";
    for (int i = 0; i < (int)files.size(); i++) {
        if (i > 0) json += ",";
        json += "\"" + files[i] + "\"";
    }
    json += "]";
    server.send(200, "application/json", json);
}

void setupWebServer() {
    server.on("/",           handleRoot);
    server.on("/config",     handleConfig);
    server.on("/saveconfig", HTTP_POST, handleSaveConfig);
    server.on("/download",   handleDownload);
    server.on("/delete",     handleDelete);
    server.on("/api/status", handleApiStatus);
    server.on("/api/files",  handleApiFiles);
    server.begin();
    Serial.println("Web server started");
}

// ─────────────────────────────────────────────────────────────────────────────
//  BLE setup
// ─────────────────────────────────────────────────────────────────────────────
void setupBLE() {
    NimBLEDevice::init("ESP32-V1");
    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    NimBLEService* pSvc = pServer->createService(SERVICE_UUID);

    // CMD char (WRITE_NR)
    NimBLECharacteristic* pCmdChar = pSvc->createCharacteristic(
        CMD_UUID, NIMBLE_PROPERTY::WRITE_NR);
    pCmdChar->setCallbacks(new CmdCallbacks());

    // FILE char (NOTIFY)
    pFileChar = pSvc->createCharacteristic(FILE_UUID, NIMBLE_PROPERTY::NOTIFY);

    // STAT char (NOTIFY)
    pStatChar = pSvc->createCharacteristic(STAT_UUID, NIMBLE_PROPERTY::NOTIFY);

    pSvc->start();

    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->enableScanResponse(true);
    pAdv->start();
    Serial.println("BLE advertising started");
}

// ─────────────────────────────────────────────────────────────────────────────
//  Setup
// ─────────────────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\nESP32-V1 starting...");

    // LED
    pixel.begin();
    ledOff();

    // I2C
    Wire.begin(SDA_PIN, SCL_PIN);

    // Sensors
    if (!aht.begin()) {
        Serial.println("AHT20 not found!");
    } else {
        Serial.println("AHT20 OK");
    }
    if (!bmp.begin(0x76)) {
        if (!bmp.begin(0x77)) {
            Serial.println("BMP280 not found!");
        } else {
            Serial.println("BMP280 OK at 0x77");
        }
    } else {
        Serial.println("BMP280 OK at 0x76");
    }
    bmp.setSampling(Adafruit_BMP280::MODE_NORMAL,
                    Adafruit_BMP280::SAMPLING_X2,
                    Adafruit_BMP280::SAMPLING_X16,
                    Adafruit_BMP280::FILTER_X16,
                    Adafruit_BMP280::STANDBY_MS_500);

    // SPIFFS
    if (SPIFFS.begin(true)) {
        fsReady = true;
        Serial.println("SPIFFS OK");
    } else {
        Serial.println("SPIFFS FAILED");
    }

    // Load NVS
    loadNvs();
    loadWifiConfig();

    // WiFi
    if (cfg_ssid.length() > 0) {
        Serial.println("Connecting to WiFi: " + cfg_ssid);
        WiFi.mode(WIFI_STA);
        WiFi.begin(cfg_ssid.c_str(), cfg_pass.c_str());
        int attempts = 0;
        while (WiFi.status() != WL_CONNECTED && attempts < 20) {
            delay(500);
            Serial.print(".");
            attempts++;
        }
        if (WiFi.status() == WL_CONNECTED) {
            wifiConnected = true;
            apMode = false;
            Serial.println("\nWiFi connected: " + WiFi.localIP().toString());

            // NTP
            configTime(cfg_tzOffset * 3600, 0, "pool.ntp.org", "time.nist.gov");
            Serial.print("Waiting for NTP sync...");
            struct tm ti;
            int ntpTry = 0;
            while (!getLocalTime(&ti) && ntpTry < 20) {
                delay(500);
                Serial.print(".");
                ntpTry++;
            }
            if (getLocalTime(&ti)) {
                ntpSynced = true;
                Serial.println("\nNTP synced: " + getFormattedDateTime());
            } else {
                Serial.println("\nNTP sync failed");
            }
        } else {
            Serial.println("\nWiFi connect failed, starting AP mode");
            wifiConnected = false;
        }
    }

    // Start AP if no WiFi or connection failed
    if (!wifiConnected) {
        WiFi.mode(WIFI_AP);
        WiFi.softAP("ESP32-V1-Config", "12345678");
        WiFi.softAPConfig(IPAddress(192,168,4,1), IPAddress(192,168,4,1), IPAddress(255,255,255,0));
        apMode = true;
        Serial.println("AP started: 192.168.4.1");
    }

    // Web server (both AP and STA mode)
    setupWebServer();

    // OTA
    ArduinoOTA.setHostname("ESP32-V1");
    ArduinoOTA.onStart([]() { Serial.println("OTA start"); });
    ArduinoOTA.onEnd([]()   { Serial.println("OTA end"); });
    ArduinoOTA.onError([](ota_error_t err) { Serial.printf("OTA error[%u]\n", err); });
    ArduinoOTA.begin();

    // BLE
    setupBLE();

    // Init file if needed
    if (fsReady && ntpSynced) {
        if (curFile.length() == 0) {
            createNewFile();
        }
    }

    Serial.println("Setup complete");
}

// ─────────────────────────────────────────────────────────────────────────────
//  Loop
// ─────────────────────────────────────────────────────────────────────────────
void loop() {
    server.handleClient();
    ArduinoOTA.handle();

    // Process pending BLE command
    if (pendingBleCmd.length() > 0 && pFileChar && pStatChar) {
        String cmd = pendingBleCmd;
        pendingBleCmd = "";
        processBleCommand(cmd);
    }

    // Sensor read every 1000ms
    unsigned long now = millis();
    if (now - lastSensorMs >= 1000) {
        lastSensorMs = now;

        // Read sensors
        sensors_event_t hum_event, temp_event;
        if (aht.getEvent(&hum_event, &temp_event)) {
            g_tempAht  = temp_event.temperature;
            g_humidity = hum_event.relative_humidity;
        }
        g_pressure = bmp.readPressure() / 100.0f;
        g_tempBmp  = bmp.readTemperature();

        // Write to SPIFFS if ready
        if (fsReady && ntpSynced) {
            if (curFile.length() == 0) {
                createNewFile();
            }
            appendRecord(g_tempAht, g_humidity, g_pressure, g_tempBmp);

            // Check if file is full
            if (recCnt >= MAX_RECORDS_PER_FILE) {
                recCnt = 0;
                slotIdx++;
                if (slotIdx >= MAX_FILES) {
                    // All 5 files full: blue LED 5 sec, reset slot
                    blueSolidMode  = true;
                    blueSolidStart = millis();
                    slotIdx = 0;
                    Serial.println("All slots full - cycling");
                }
                curFile = "";
                saveNvs();
                // New file created on next iteration when curFile == ""
            }
        } else if (wifiConnected && !ntpSynced) {
            // Try NTP sync again
            struct tm ti;
            if (getLocalTime(&ti)) {
                ntpSynced = true;
                Serial.println("NTP synced (delayed)");
                if (fsReady && curFile.length() == 0) {
                    createNewFile();
                }
            }
        }
    }

    // Update LED
    updateLed();
}
