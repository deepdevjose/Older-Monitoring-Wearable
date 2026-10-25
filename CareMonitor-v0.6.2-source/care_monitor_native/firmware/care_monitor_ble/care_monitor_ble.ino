/*
 * Care Monitor v0.6.0 - ESP32 BLE sensor peripheral.
 * Derived from the team's data_API sensor sketch; no network credentials.
 * Intended board: original ESP32 Dev Module, Arduino-ESP32 3.3.11 (Bluedroid).
 * BLE is a foreground classroom prototype, with no pairing or encryption.
 */
#include <Arduino.h>
#include <Wire.h>
#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <atomic>
#include <math.h>
#include "MAX30105.h"
#include "spo2_algorithm.h"

#define SDA_PIN 21
#define SCL_PIN 22
#define MIC_PIN 34
#define PHOTO_PIN 35
#define BUZZER_PIN 25
#define MAX30102_ADDRESS 0x57
#define GY906_ADDRESS 0x5A
#define MPU6050_ADDRESS 0x68
#define DEVICE_ID "elder-demo-01"

static const char *BLE_NAME = "CareMonitor-elder-demo-01";
static const char *SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char *TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
static constexpr uint32_t REPORT_INTERVAL = 1000;
static constexpr uint32_t FRAGMENT_INTERVAL_MS = 8;
static constexpr uint32_t SUBSCRIPTION_SETTLE_MS = 100;
static constexpr size_t FRAGMENT_BYTES = 20; // Works at the default ATT MTU of 23.
static constexpr uint32_t FRAME_STALL_MS = 4000;

BLEServer *bleServer = nullptr;
BLECharacteristic *txCharacteristic = nullptr;
BLE2902 *txCccd = nullptr;
std::atomic<bool> bleConnected{false};
std::atomic<bool> bleSubscribed{false};
std::atomic<bool> restartAdvertising{false};
std::atomic<uint32_t> disconnectedAt{0};
std::atomic<uint32_t> streamEpoch{0};
std::atomic<bool> notificationAccepted{false};

// Only accessed by the Arduino loop, never mutated in a BLE callback.
String pendingFrame;
size_t frameOffset = 0;
uint32_t frameEpoch = 0;
uint32_t handledEpoch = 0;
uint32_t lastFragment = 0;
uint32_t frameStarted = 0;
uint32_t lastReport = 0;
uint64_t sampleId = 0; // Strictly increasing within one boot.
char bootNonce[17] = {0};

class MonitorServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    bleSubscribed.store(false);
    txCccd->setNotifications(false);
    streamEpoch.fetch_add(1);
    restartAdvertising.store(false);
    bleConnected.store(true);
  }
  void onDisconnect(BLEServer *) override {
    bleConnected.store(false);
    bleSubscribed.store(false);
    streamEpoch.fetch_add(1);
    disconnectedAt.store(millis());
    restartAdvertising.store(true);
  }
};

class SubscriptionCallbacks : public BLEDescriptorCallbacks {
  void onWrite(BLEDescriptor *descriptor) override {
    const bool enabled = descriptor->getLength() == 2 &&
        (descriptor->getValue()[0] & 1u) != 0;
    bleSubscribed.store(bleConnected.load() && enabled);
    // Start at a complete object after every (re)subscription.
    streamEpoch.fetch_add(1);
  }
};

class MonitorTxCallbacks : public BLECharacteristicCallbacks {
  void onStatus(BLECharacteristic *, Status status, uint32_t) override {
    // In the pinned Bluedroid implementation notify() invokes this synchronously
    // after the local GATT enqueue. It is not a phone/application acknowledgment.
    notificationAccepted.store(status == Status::SUCCESS_NOTIFY);
  }
};

void initBLE() {
  BLEDevice::init(BLE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new MonitorServerCallbacks());
  BLEService *service = bleServer->createService(SERVICE_UUID);
  txCharacteristic = service->createCharacteristic(
      TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  txCharacteristic->setCallbacks(new MonitorTxCallbacks());
  txCccd = new BLE2902();
  txCccd->setCallbacks(new SubscriptionCallbacks());
  txCharacteristic->addDescriptor(txCccd);
  service->start();

  // Service UUID in the primary advertisement, full name in scan response.
  // Both fit within their own 31-byte legacy-advertising payloads.
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  BLEAdvertisementData advertised;
  advertised.setFlags(0x06);
  advertised.setCompleteServices(BLEUUID(SERVICE_UUID));
  advertising->setAdvertisementData(advertised);
  BLEAdvertisementData response;
  response.setName(BLE_NAME);
  advertising->setScanResponseData(response);
  advertising->setScanResponse(true);
  advertising->start();
  snprintf(bootNonce, sizeof(bootNonce), "%08lx%08lx",
      static_cast<unsigned long>(esp_random()),
      static_cast<unsigned long>(esp_random()));
}

void serviceBLE() {
  const uint32_t now = millis();
  const uint32_t epoch = streamEpoch.load();
  if (epoch != handledEpoch) {
    pendingFrame = "";
    frameOffset = 0;
    handledEpoch = epoch;
    // Leave time for the CCCD acknowledgment to reach the phone. This is a
    // timestamp offset, not a blocking delay; sensor sampling keeps running.
    lastReport = now - REPORT_INTERVAL + SUBSCRIPTION_SETTLE_MS;
  }
  if (!bleConnected.load()) {
    if (restartAdvertising.load() &&
        static_cast<uint32_t>(now - disconnectedAt.load()) >= 100) {
      restartAdvertising.store(false);
      BLEDevice::getAdvertising()->start();
    }
    return;
  }
  if (!bleSubscribed.load() || frameEpoch != epoch || pendingFrame.isEmpty()) {
    return;
  }
  if (static_cast<uint32_t>(now - frameStarted) >= FRAME_STALL_MS) {
    // A congested connection must not hold a partial object forever. Disconnect
    // instead of concatenating a new object onto an incomplete old object.
    bleSubscribed.store(false);
    pendingFrame = "";
    bleServer->disconnect(bleServer->getConnId());
    return;
  }
  if (static_cast<uint32_t>(now - lastFragment) < FRAGMENT_INTERVAL_MS) {
    return;
  }
  lastFragment = now;
  const size_t remaining = pendingFrame.length() - frameOffset;
  const size_t count = remaining < FRAGMENT_BYTES ? remaining : FRAGMENT_BYTES;
  txCharacteristic->setValue(
      reinterpret_cast<const uint8_t *>(pendingFrame.c_str() + frameOffset), count);
  notificationAccepted.store(false);
  if (streamEpoch.load() != epoch || !bleSubscribed.load()) return;
  txCharacteristic->notify();
  if (notificationAccepted.load()) {
    frameOffset += count;
    if (frameOffset == pendingFrame.length()) {
      pendingFrame = "";
      frameOffset = 0;
    }
  }
  // If the local GATT queue is full, retry this fragment next time; no delay,
  // no frame replacement and no reset of optical sensor FIFO or sample window.
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
bool estimateCalculated = false;
uint32_t lastPPGSample = 0;

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
  estimateCalculated = false;
  lastPPGSample = millis();

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
    lastPPGSample = millis();

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
      estimateCalculated = false;

      continue;
    }


    // -------------------------------------------------
    // Finger detected
    // -------------------------------------------------

    // A completed estimate remains current between window calculations.
    // The original sketch accidentally reset this status on every sample.
    if (!estimateCalculated)
    {
      maxStatus = "warming_up";
    }


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
      estimateCalculated = true;


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
  if (static_cast<uint32_t>(millis() - lastPPGSample) >= 2000)
  {
    // Do not keep reporting an old successful estimate if samples stop.
    // A new contiguous window is needed after recovery; the FIFO is untouched.
    maxStatus = "read_error";
    fingerDetected = false;
    hrValid = false;
    spo2Valid = false;
    heartRate = 0;
    spo2 = 0;
    ppgCount = 0;
    estimateCalculated = false;
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

void queueSnapshot()
{
  JsonDocument doc;


  // -------------------------------------------------
  // Device
  // -------------------------------------------------

  doc["device_id"] = DEVICE_ID;

  // Device monotonic time is not a wall-clock timestamp. The phone adds UTC time.
  doc["uptime_ms"] = static_cast<uint64_t>(esp_timer_get_time() / 1000);
  doc["boot_nonce"] = bootNonce;
  doc["sample_id"] = ++sampleId;


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
  // Only the main loop owns this String. Never replace a frame in flight.
  // The sole newline terminates the UTF-8 JSON object after reassembly.
  pendingFrame = json + "\n";
  frameOffset = 0;
  frameEpoch = streamEpoch.load();
  lastFragment = millis() - FRAGMENT_INTERVAL_MS;
  frameStarted = millis();
}

// =====================================================
// SETUP AND CONTINUOUS SENSOR LOOP
// =====================================================
void setup() {
  Serial.begin(115200);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  pinMode(MIC_PIN, INPUT);
  pinMode(PHOTO_PIN, INPUT);
  analogReadResolution(12);
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);
  Wire.setTimeOut(20);
  initMPU();
  initGY906();
  initMAX30102();
  initBLE();
  Serial.println("Care Monitor BLE initialized. Connect in the Android app.");
  Serial.println("Place a finger steadily on MAX30102; invalid estimates stay null.");
}

void loop() {
  // Drain the optical FIFO frequently, including during BLE transmission.
  updateMAX30102();
  serviceBLE();

  const uint32_t now = millis();
  static uint32_t lastMPU = 0;
  static uint32_t lastTemp = 0;
  static uint32_t lastAnalog = 0;
  if (static_cast<uint32_t>(now - lastMPU) >= 50) {
    lastMPU = now;
    updateMPU();
  }
  if (static_cast<uint32_t>(now - lastTemp) >= 1000) {
    lastTemp = now;
    updateGY906();
  }
  if (static_cast<uint32_t>(now - lastAnalog) >= 100) {
    lastAnalog = now;
    updateMicrophone();
    updatePhotoresistor();
  }
  updateMAX30102();

  if (static_cast<uint32_t>(now - lastReport) >= REPORT_INTERVAL) {
    lastReport = now;
    // One bounded frame in flight. A slow link skips a report rather than
    // starving optical sampling or interleaving parts of two JSON objects.
    if (bleConnected.load() && bleSubscribed.load() && pendingFrame.isEmpty()) {
      queueSnapshot();
    }
  }
  serviceBLE();
  delay(1); // Yield once to ESP32 system tasks; no per-frame waiting loop.
}
