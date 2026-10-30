#include <Arduino.h>
#include <Wire.h>
#include <ArduinoJson.h>
#include <WiFi.h>
#include "secrets.h"
#include "WearableBluetooth.h"
#include "WearableSession.h"
#include <esp_random.h>
#include <mbedtls/md.h>
#include <time.h>

#include "MAX30105.h"
#include "spo2_algorithm.h"

#include <math.h>


// =====================================================
// PIN CONFIGURATION
// =====================================================

#define SDA_PIN     21
#define SCL_PIN     22

#define BUZZER_PIN  25

// =====================================================
// I2C ADDRESSES
// =====================================================

#define MAX30102_ADDRESS  0x57
#define GY906_ADDRESS     0x5A
#define MPU6050_ADDRESS   0x68


// =====================================================
// GENERAL SETTINGS
// =====================================================

#define DEVICE_ID "elder-demo-01"
#define REPORT_INTERVAL 1000


#define BT_DEVICE_NAME "ElderSensors-elder-demo-01"
#define WIFI_RETRY_INTERVAL 30000
#define NTP_SERVER "pool.ntp.org"
#define SHANGHAI_TZ "CST-8"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED) || !defined(CONFIG_BT_SPP_ENABLED)
#error "Bluetooth SPP requires a classic ESP32 board with Bluetooth enabled."
#endif

#ifndef WEARABLE_SETUP_KEY_HEX
#error "Add a unique 128-bit WEARABLE_SETUP_KEY_HEX to private secrets.h before commissioning."
#endif
static_assert(sizeof(WEARABLE_SETUP_KEY_HEX) == 33, "Wearable key must contain exactly 32 hex characters.");

WearableBluetooth wearableBluetooth;
bool wearableReady = false;
String pendingBluetoothReply;
uint8_t wearableKey[16];
bool wearableKeyValid = false;

std::string wearableProof(const std::string &message)
{
  if (!wearableKeyValid) return "";
  uint8_t digest[32];
  const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (!md || mbedtls_md_hmac(md, wearableKey, sizeof(wearableKey),
      reinterpret_cast<const unsigned char *>(message.data()), message.size(), digest) != 0) return "";
  static const char digits[] = "0123456789abcdef";
  std::string result(64, '0');
  for (size_t i = 0; i < sizeof(digest); ++i) {
    result[2*i] = digits[digest[i] >> 4]; result[2*i+1] = digits[digest[i] & 15];
  }
  return result;
}

WearableSession wearableSession(wearableProof);

std::string bluetoothNonce()
{
  uint8_t bytes[16]; esp_fill_random(bytes, sizeof(bytes));
  static const char digits[] = "0123456789abcdef";
  std::string result(32, '0');
  for (size_t i = 0; i < sizeof(bytes); ++i) {
    result[2*i] = digits[bytes[i] >> 4]; result[2*i+1] = digits[bytes[i] & 15];
  }
  return result;
}

void processBluetoothAuthentication()
{
  wearableBluetooth.poll();
  const uint32_t session = wearableBluetooth.generation();
  if (!wearableBluetooth.connected()) {
    wearableReady = false; pendingBluetoothReply = ""; wearableSession.clear(); return;
  }
  if (wearableSession.session() != session) {
    wearableReady = false; pendingBluetoothReply = "";
    wearableSession.start(session, millis());
    Serial.println("Bluetooth phone connected; waiting for app authentication.");
  }
  if (!wearableReady && wearableSession.expired(millis())) {
    pendingBluetoothReply = ""; wearableBluetooth.disconnect(session);
    Serial.println("Bluetooth app authentication expired; connection closed."); return;
  }
  String line; uint32_t incomingSession = 0;
  while (wearableBluetooth.readLine(line, incomingSession)) {
    if (incomingSession != session) continue;
    std::string reply;
    if (!pendingBluetoothReply.isEmpty() || !wearableSession.receive(line.c_str(), bluetoothNonce(), reply)) {
      wearableReady = false; pendingBluetoothReply = ""; wearableBluetooth.disconnect(session);
      Serial.println("Bluetooth app authentication rejected; connection closed."); return;
    }
    pendingBluetoothReply = reply.c_str();
  }
  if (!pendingBluetoothReply.isEmpty() && wearableBluetooth.sendLine(pendingBluetoothReply, session)) {
    pendingBluetoothReply = "";
    if (wearableSession.authenticated()) {
      wearableReady = true;
      Serial.println("Bluetooth app authenticated; exclusive sensor stream enabled.");
    }
    wearableBluetooth.poll();
  }
}

unsigned long lastReport = 0;
unsigned long lastWiFiRetry = 0;
bool wifiInfoPrinted = false;
bool timeSyncStarted = false;
portMUX_TYPE wifiEventMux = portMUX_INITIALIZER_UNLOCKED;
volatile uint16_t wifiDisconnectReason = 0;
volatile bool wifiDisconnectPending = false;
bool wifiDiagnosticScanStarted = false;
bool wifiDiagnosticScanFinished = false;

// Wi-Fi callbacks run on another task. Keep serial output in loop(), between
// complete JSON lines, and never print credentials or neighbouring networks.
void noteWiFiDisconnect(WiFiEvent_t event, WiFiEventInfo_t info)
{
  portENTER_CRITICAL(&wifiEventMux);
  wifiDisconnectReason = info.wifi_sta_disconnected.reason;
  wifiDisconnectPending = true;
  portEXIT_CRITICAL(&wifiEventMux);
}

void updateWiFiDiagnostics()
{
  portENTER_CRITICAL(&wifiEventMux);
  const bool pending = wifiDisconnectPending;
  const uint16_t reason = wifiDisconnectReason;
  wifiDisconnectPending = false;
  portEXIT_CRITICAL(&wifiEventMux);
  if (pending)
  {
    Serial.printf("Wi-Fi disconnected; reason=%u status=%d.\n", reason, static_cast<int>(WiFi.status()));
  }

  // One asynchronous, target-only scan after an unsuccessful association.
  // ESP32 Classic scans 2.4 GHz; this distinguishes an absent compatible AP
  // from an authentication failure without collecting other SSIDs or MACs.
  if (!wifiDiagnosticScanStarted && millis() >= 15000 && WiFi.status() == WL_NO_SSID_AVAIL)
  {
    wifiDiagnosticScanStarted = true;
    WiFi.scanNetworks(true, false, false, 120, 0, WIFI_SSID);
  }
  if (wifiDiagnosticScanStarted && !wifiDiagnosticScanFinished)
  {
    const int16_t found = WiFi.scanComplete();
    if (found >= 0)
    {
      int matching = 0;
      for (int16_t index = 0; index < found; ++index)
      {
        if (WiFi.SSID(index) == WIFI_SSID)
        {
          ++matching;
          Serial.printf("Wi-Fi target AP: channel=%d RSSI=%d auth=%d.\n",
                        WiFi.channel(index), WiFi.RSSI(index), static_cast<int>(WiFi.encryptionType(index)));
        }
      }
      Serial.printf("Wi-Fi target 2.4 GHz scan: matches=%d.\n", matching);
      WiFi.scanDelete();
      wifiDiagnosticScanFinished = true;
    }
    else if (found == WIFI_SCAN_FAILED)
    {
      Serial.println("Wi-Fi target scan unavailable; association will retry normally.");
      wifiDiagnosticScanFinished = true;
    }
  }
}


// =====================================================
// MAX30102
// =====================================================

MAX30105 optical;

const int PPG_SAMPLES = 100;
const int PPG_STEP = 25;

uint32_t irBuffer[PPG_SAMPLES];
uint32_t redBuffer[PPG_SAMPLES];

int ppgCount = 0;

uint32_t latestIR = 0;
uint32_t latestRed = 0;

bool maxOnline = false;
bool fingerDetected = false;

bool hrValid = false;
bool spo2Valid = false;

int32_t heartRate = 0;
int32_t spo2 = 0;

const uint32_t FINGER_THRESHOLD = 50000;

String maxStatus = "not_found";


// =====================================================
// MPU6050
// =====================================================

bool mpuOnline = false;

float accelX = 0;
float accelY = 0;
float accelZ = 0;

float gyroX = 0;
float gyroY = 0;
float gyroZ = 0;


// =====================================================
// GY-906 / MLX90614
// =====================================================

bool gyOnline = false;

float objectTemp = NAN;
float ambientTemp = NAN;


// =====================================================
// MICROPHONE
// =====================================================

int micValue = 0;


// =====================================================
// PHOTORESISTOR
// =====================================================

int photoValue = 0;


// =====================================================
// I2C HELPER
// =====================================================

bool i2cDeviceExists(uint8_t address)
{
  Wire.beginTransmission(address);

  return Wire.endTransmission() == 0;
}


// =====================================================
// READ I2C BYTES
// =====================================================

bool readBytes(
  uint8_t address,
  uint8_t reg,
  uint8_t *buffer,
  uint8_t length
)
{
  Wire.beginTransmission(address);

  Wire.write(reg);

  if (Wire.endTransmission(false) != 0)
  {
    return false;
  }

  uint8_t received =
    Wire.requestFrom(address, length, true);

  if (received != length)
  {
    return false;
  }

  for (uint8_t i = 0; i < length; i++)
  {
    buffer[i] = Wire.read();
  }

  return true;
}


// =====================================================
// WRITE I2C BYTE
// =====================================================

bool writeByte(
  uint8_t address,
  uint8_t reg,
  uint8_t value
)
{
  Wire.beginTransmission(address);

  Wire.write(reg);
  Wire.write(value);

  return Wire.endTransmission() == 0;
}


// =====================================================
// INITIALIZE MPU6050
// =====================================================

void initMPU()
{
  if (!i2cDeviceExists(MPU6050_ADDRESS))
  {
    Serial.println("MPU6050 not found");

    mpuOnline = false;

    return;
  }

  // Wake MPU6050
  if (!writeByte(
        MPU6050_ADDRESS,
        0x6B,
        0x00
      ))
  {
    mpuOnline = false;

    return;
  }

  mpuOnline = true;

  Serial.println("MPU6050 found");
}


// =====================================================
// UPDATE MPU6050
// =====================================================

void updateMPU()
{
  if (!mpuOnline)
  {
    return;
  }

  uint8_t data[14];

  if (!readBytes(
        MPU6050_ADDRESS,
        0x3B,
        data,
        14
      ))
  {
    mpuOnline = false;

    return;
  }

  int16_t rawAX =
    (data[0] << 8) | data[1];

  int16_t rawAY =
    (data[2] << 8) | data[3];

  int16_t rawAZ =
    (data[4] << 8) | data[5];


  int16_t rawGX =
    (data[8] << 8) | data[9];

  int16_t rawGY =
    (data[10] << 8) | data[11];

  int16_t rawGZ =
    (data[12] << 8) | data[13];


  accelX = rawAX / 16384.0;
  accelY = rawAY / 16384.0;
  accelZ = rawAZ / 16384.0;


  gyroX = rawGX / 131.0;
  gyroY = rawGY / 131.0;
  gyroZ = rawGZ / 131.0;
}


// =====================================================
// READ MLX90614 TEMPERATURE
// =====================================================

float readMLXTemperature(uint8_t reg)
{
  Wire.beginTransmission(GY906_ADDRESS);

  Wire.write(reg);

  if (Wire.endTransmission(false) != 0)
  {
    return NAN;
  }

  Wire.requestFrom(
    GY906_ADDRESS,
    (uint8_t)3
  );

  if (Wire.available() < 3)
  {
    return NAN;
  }

  uint8_t low = Wire.read();
  uint8_t high = Wire.read();

  // PEC byte
  Wire.read();

  uint16_t raw =
    ((uint16_t)high << 8) | low;

  float temperature =
    raw * 0.02 - 273.15;

  return temperature;
}


// =====================================================
// INITIALIZE GY906
// =====================================================

void initGY906()
{
  gyOnline =
    i2cDeviceExists(GY906_ADDRESS);

  if (gyOnline)
  {
    Serial.println("GY-906 found");
  }
  else
  {
    Serial.println("GY-906 not found");
  }
}


// =====================================================
// UPDATE GY906
// =====================================================

void updateGY906()
{
  if (!gyOnline)
  {
    return;
  }

  ambientTemp =
    readMLXTemperature(0x06);

  objectTemp =
    readMLXTemperature(0x07);

  if (!isfinite(ambientTemp) || !isfinite(objectTemp))
  {
    gyOnline = false;
    ambientTemp = NAN;
    objectTemp = NAN;
  }
}


// =====================================================
// INITIALIZE MAX30102
// =====================================================

void initMAX30102()
{
  Serial.println("Initializing MAX30102...");


  maxOnline = optical.begin(
    Wire,
    I2C_SPEED_STANDARD,
    MAX30102_ADDRESS
  );


  if (!maxOnline)
  {
    Serial.println("MAX30102 not found");

    maxStatus = "not_found";

    return;
  }


  Serial.println("MAX30102 found");


  /*
      LED brightness = 60
      Sample average = 4
      LED mode = 2 (Red + IR)
      Sample rate = 100
      Pulse width = 411
      ADC range = 4096
  */

  optical.setup(
    60,
    4,
    2,
    100,
    411,
    4096
  );


  optical.setPulseAmplitudeRed(0x1F);

  optical.setPulseAmplitudeIR(0x1F);

  optical.setPulseAmplitudeGreen(0);


  ppgCount = 0;

  maxStatus = "waiting";
}


// =====================================================
// UPDATE MAX30102
// =====================================================

void updateMAX30102()
{
  if (!maxOnline)
  {
    return;
  }


  /*
      Pull new samples from the MAX30102 hardware FIFO.

      IMPORTANT:
      We do NOT manually reset the FIFO anymore.
  */

  optical.check();


  while (optical.available())
  {

    uint32_t red =
      optical.getFIFORed();

    uint32_t ir =
      optical.getFIFOIR();


    optical.nextSample();


    latestRed = red;
    latestIR = ir;


    // -------------------------------------------------
    // Finger detection
    // -------------------------------------------------

    fingerDetected =
      (ir > FINGER_THRESHOLD);


    if (!fingerDetected)
    {
      maxStatus = "no_finger";

      hrValid = false;
      spo2Valid = false;

      heartRate = 0;
      spo2 = 0;

      ppgCount = 0;

      continue;
    }


    // -------------------------------------------------
    // Finger detected
    // -------------------------------------------------

    maxStatus = "warming_up";


    // Store sample

    irBuffer[ppgCount] = ir;

    redBuffer[ppgCount] = red;

    ppgCount++;


    // -------------------------------------------------
    // Wait until 100 samples collected
    // -------------------------------------------------

    if (ppgCount >= PPG_SAMPLES)
    {

      int8_t spo2ValidFlag = 0;
      int8_t heartRateValidFlag = 0;


      maxim_heart_rate_and_oxygen_saturation(
        irBuffer,
        PPG_SAMPLES,
        redBuffer,
        &spo2,
        &spo2ValidFlag,
        &heartRate,
        &heartRateValidFlag
      );


      // -------------------------------------------------
      // Validate heart rate
      // -------------------------------------------------

      hrValid =
        heartRateValidFlag &&
        heartRate >= 30 &&
        heartRate <= 220;


      // -------------------------------------------------
      // Validate SpO2
      // -------------------------------------------------

      spo2Valid =
        spo2ValidFlag &&
        spo2 >= 70 &&
        spo2 <= 100;


      // -------------------------------------------------
      // Sensor status
      // -------------------------------------------------

      if (hrValid && spo2Valid)
      {
        maxStatus = "ok";
      }
      else if (hrValid || spo2Valid)
      {
        maxStatus = "partial";
      }
      else
      {
        maxStatus = "poor_signal";
      }


      // -------------------------------------------------
      // Keep newest 75 samples
      // -------------------------------------------------

      memmove(
        irBuffer,
        irBuffer + PPG_STEP,
        (PPG_SAMPLES - PPG_STEP)
          * sizeof(irBuffer[0])
      );


      memmove(
        redBuffer,
        redBuffer + PPG_STEP,
        (PPG_SAMPLES - PPG_STEP)
          * sizeof(redBuffer[0])
      );


      ppgCount =
        PPG_SAMPLES - PPG_STEP;
    }
  }
}


// =====================================================
// UPDATE MICROPHONE
// =====================================================

void updateMicrophone()
{
  micValue =
    analogRead(MIC_PIN);
}


// =====================================================
// UPDATE PHOTORESISTOR
// =====================================================

void updatePhotoresistor()
{
  photoValue =
    analogRead(PHOTO_PIN);
}


// =====================================================
// PRINT JSON
// =====================================================

void printJSON()
{
  JsonDocument doc;


  // -------------------------------------------------
  // Device
  // -------------------------------------------------

  doc["device_id"] = DEVICE_ID;

  doc["uptime_ms"] = millis();

  struct tm localTime;
  if (getLocalTime(&localTime, 0))
  {
    char timestamp[32];
    strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%S+08:00", &localTime);
    doc["timestamp"] = timestamp;
  }


  // -------------------------------------------------
  // MAX30102
  // -------------------------------------------------

  JsonObject max =
    doc["max30102"].to<JsonObject>();


  max["i2c_address"] = "0x57";

  max["status"] = maxStatus;

  max["finger_detected"] =
    fingerDetected;


  max["heart_rate_valid"] =
    hrValid;

  max["spo2_valid"] =
    spo2Valid;


  if (hrValid)
  {
    max["heart_rate_bpm"] =
      heartRate;
  }
  else
  {
    max["heart_rate_bpm"] =
      nullptr;
  }


  if (spo2Valid)
  {
    max["spo2_percent"] =
      spo2;
  }
  else
  {
    max["spo2_percent"] =
      nullptr;
  }


  max["ir_raw"] =
    latestIR;

  max["red_raw"] =
    latestRed;
  

  max["window_samples"] =
    ppgCount;

  max["window_target"] =
    PPG_SAMPLES;


  // -------------------------------------------------
  // MPU6050
  // -------------------------------------------------

  JsonObject mpu =
    doc["mpu6050"].to<JsonObject>();


  mpu["i2c_address"] = "0x68";

  mpu["online"] =
    mpuOnline;


  JsonObject accel =
    mpu["accel_g"].to<JsonObject>();


  accel["x"] = accelX;
  accel["y"] = accelY;
  accel["z"] = accelZ;


  JsonObject gyro =
    mpu["gyro_dps"].to<JsonObject>();


  gyro["x"] = gyroX;
  gyro["y"] = gyroY;
  gyro["z"] = gyroZ;


  // -------------------------------------------------
  // GY906
  // -------------------------------------------------

  JsonObject temp =
    doc["gy906"].to<JsonObject>();


  temp["i2c_address"] = "0x5A";

  temp["online"] =
    gyOnline;


  if (
    gyOnline &&
    !isnan(objectTemp)
  )
  {
    temp["object_temp_c"] =
      objectTemp;
  }
  else
  {
    temp["object_temp_c"] =
      nullptr;
  }


  if (
    gyOnline &&
    !isnan(ambientTemp)
  )
  {
    temp["ambient_temp_c"] =
      ambientTemp;
  }
  else
  {
    temp["ambient_temp_c"] =
      nullptr;
  }


  // -------------------------------------------------
  // Microphone
  // -------------------------------------------------

  JsonObject microphone =
    doc["microphone"].to<JsonObject>();


  microphone["pin"] =
    MIC_PIN;

  microphone["adc_raw"] =
    micValue;


  // -------------------------------------------------
  // Photoresistor
  // -------------------------------------------------

  JsonObject photo =
    doc["photoresistor"].to<JsonObject>();


  photo["pin"] =
    PHOTO_PIN;

  photo["adc_raw"] =
    photoValue;


  photo["percent"] =
    photoValue * 100.0 / 4095.0;


  // -------------------------------------------------
  // Buzzer
  // -------------------------------------------------

  JsonObject buzzer =
    doc["buzzer"].to<JsonObject>();


  buzzer["pin"] =
    BUZZER_PIN;


  // -------------------------------------------------
  // Print JSON
  // -------------------------------------------------

  String json;
  serializeJson(doc, json);
  Serial.println(json);

  if (!wearableReady || !wearableBluetooth.connected() ||
      wearableSession.session() != wearableBluetooth.generation())
  {
    Serial.println("Bluetooth SPP skipped: no phone is connected.");
    return;
  }

  if (!wearableBluetooth.sendLine(json, wearableSession.session()))
  {
    // Never block sensor acquisition or carry a record across phone sessions.
    Serial.println("Bluetooth SPP busy: this record was not queued.");
    return;
  }
  wearableBluetooth.poll();
  Serial.printf("Bluetooth SPP -> queued %u-byte sensor record.\n",
                static_cast<unsigned int>(json.length()));
}


// =====================================================
// SETUP
// =====================================================

void setup()
{
  Serial.begin(115200);

  delay(1000);

  const std::string setupKey(WEARABLE_SETUP_KEY_HEX);
  wearableKeyValid = WearableSession::hex(setupKey, 32);
  if (wearableKeyValid) {
    for (size_t i = 0; i < sizeof(wearableKey); ++i) {
      const char hi = setupKey[2*i], lo = setupKey[2*i+1];
      wearableKey[i] = ((hi <= '9' ? hi-'0' : hi-'a'+10) << 4) | (lo <= '9' ? lo-'0' : lo-'a'+10);
    }
  }
  if (!wearableKeyValid || !wearableBluetooth.begin(BT_DEVICE_NAME))
  {
    Serial.println("Bluetooth SPP failed to start; check private wearable setup and classic ESP32 support.");
  }
  else
  {
    Serial.printf("Bluetooth SPP ready: %s\n", BT_DEVICE_NAME);
    Serial.println("Pair from the phone; authorize this wearable in the app. No serial approval is required.");
  }

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.onEvent(noteWiFiDisconnect, ARDUINO_EVENT_WIFI_STA_DISCONNECTED);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.println();
  Serial.println("Starting system...");
  Serial.printf("Connecting to hotspot: %s\n", WIFI_SSID);


  // -------------------------------------------------
  // Pins
  // -------------------------------------------------

  pinMode(
    BUZZER_PIN,
    OUTPUT
  );

  digitalWrite(
    BUZZER_PIN,
    LOW
  );


  pinMode(
    MIC_PIN,
    INPUT
  );

  pinMode(
    PHOTO_PIN,
    INPUT
  );


  analogReadResolution(12);


  // -------------------------------------------------
  // I2C
  // -------------------------------------------------

  Wire.begin(
    SDA_PIN,
    SCL_PIN
  );


  Wire.setClock(100000);


  // -------------------------------------------------
  // Sensors
  // -------------------------------------------------

  initMPU();

  initGY906();

  initMAX30102();


  Serial.println();
  Serial.println(
    "System initialized."
  );

  Serial.println("Wi-Fi connection is handled in the background.");
  Serial.println(
    "Place finger steadily on MAX30102."
  );

  Serial.println();
}


// =====================================================
// LOOP
// =====================================================

void loop()
{
  processBluetoothAuthentication();
  updateWiFiDiagnostics();

  if (WiFi.status() == WL_CONNECTED)
  {
    if (!wifiInfoPrinted)
    {
      wifiInfoPrinted = true;
      Serial.print("Wi-Fi connected. ESP32 IP: ");
      Serial.println(WiFi.localIP());
      Serial.print("Wi-Fi gateway: ");
      Serial.println(WiFi.gatewayIP());
    }

    if (!timeSyncStarted)
    {
      configTzTime(SHANGHAI_TZ, NTP_SERVER);
      timeSyncStarted = true;
      Serial.println("Time synchronization started (Asia/Shanghai, UTC+8).");
    }
  }
  else if (millis() - lastWiFiRetry >= WIFI_RETRY_INTERVAL)
  {
    lastWiFiRetry = millis();
    wifiInfoPrinted = false;
    timeSyncStarted = false;
    WiFi.disconnect();
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.println("Retrying Wi-Fi connection...");
  }

  /*
      MAX30102 gets highest priority.

      This function must run very frequently.
  */

  updateMAX30102();


  // Other sensors

  // A transient boot-time NACK or unplug must not latch a sensor offline for
  // the entire session. Reprobe only missing addresses, at a bounded interval.
  static unsigned long lastSensorRetry = 0;
  if (millis() - lastSensorRetry >= 5000)
  {
    lastSensorRetry = millis();
    if (!mpuOnline && i2cDeviceExists(MPU6050_ADDRESS)) initMPU();
    if (!gyOnline && i2cDeviceExists(GY906_ADDRESS)) initGY906();
  }

  static unsigned long lastMPU = 0;

  if (
    millis() - lastMPU >= 50
  )
  {
    lastMPU = millis();

    updateMPU();
  }


  static unsigned long lastTemp = 0;

  if (
    millis() - lastTemp >= 1000
  )
  {
    lastTemp = millis();

    updateGY906();
  }


  static unsigned long lastAnalog = 0;

  if (
    millis() - lastAnalog >= 100
  )
  {
    lastAnalog = millis();

    updateMicrophone();

    updatePhotoresistor();
  }


  // -------------------------------------------------
  // JSON every second
  // -------------------------------------------------

  if (
    millis() - lastReport
      >= REPORT_INTERVAL
  )
  {
    lastReport = millis();

    printJSON();
  }


  // Let ESP32 background tasks run

  delay(1);
}
