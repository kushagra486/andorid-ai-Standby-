// NØVA smart-home device for ESP32 (Arduino core).
// Exposes POST /command  {"command":"on|off|toggle"}  with  Authorization: Bearer <TOKEN>.
// Add it in NØVA: Settings → Smart home → Add device → Address http://<esp32-ip>/command
// Plain HTTP is accepted by NØVA only for private LAN addresses; keep the token secret.
#include <WiFi.h>
#include <WebServer.h>

const char* WIFI_SSID = "your-wifi";
const char* WIFI_PASS = "your-password";
const char* TOKEN     = "change-me-to-a-long-random-string";
const int   RELAY_PIN = 2;          // onboard LED on many boards; use your relay pin

WebServer server(80);
bool state = false;

void apply() { digitalWrite(RELAY_PIN, state ? HIGH : LOW); }

void handleCommand() {
  if (server.header("Authorization") != String("Bearer ") + TOKEN) { server.send(401, "application/json", "{\"error\":\"unauthorized\"}"); return; }
  String body = server.arg("plain");
  if (body.indexOf("\"toggle\"") >= 0) state = !state;
  else if (body.indexOf("\"on\"") >= 0) state = true;
  else if (body.indexOf("\"off\"") >= 0) state = false;
  else { server.send(400, "application/json", "{\"error\":\"unknown command\"}"); return; }
  apply();
  server.send(200, "application/json", state ? "{\"state\":\"on\"}" : "{\"state\":\"off\"}");
}

void setup() {
  pinMode(RELAY_PIN, OUTPUT); apply();
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) delay(300);
  Serial.begin(115200); Serial.println(WiFi.localIP());
  const char* keys[] = {"Authorization"};
  server.collectHeaders(keys, 1);
  server.on("/command", HTTP_POST, handleCommand);
  server.begin();
}

void loop() { server.handleClient(); }
