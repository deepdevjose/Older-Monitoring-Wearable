#include <Arduino.h>
#include <Wire.h>
#include <ArduinoJson.h>
#include <WiFi.h>
#include "secrets.h"
#include <BluetoothSerial.h>
#include <time.h>

#include "MAX30105.h"
#include "spo2_algorithm.h"
#include "PpgReadingState.h"
#include "PpgFifoReader.h"

#include <math.h>


// =====================================================
// PIN CONFIGURATION
// =====================================================

#define SDA_PIN     21
#define SCL_PIN     22

#define PHOTO_PIN   35
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
#define WIFI_RETRY_INTERVAL 10000
#define NTP_SERVER "pool.ntp.org"
#define SHANGHAI_TZ "CST-8"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED) || !defined(CONFIG_BT_SPP_ENABLED)
#error "Bluetooth SPP requires a classic ESP32 board with Bluetooth enabled."
#endif

BluetoothSerial SerialBT;
volatile bool pairingConfirmationPending = false;
volatile uint32_t pairingConfirmationStartedAt = 0;

void confirmBluetoothPairing(uint32_t numericCode)
{
  pairingConfirmationStartedAt = millis();
  pairingConfirmationPending = true;
  Serial.printf("Compare pairing code %06lu with the phone, then type Y to accept: ",
                static_cast<unsigned long>(numericCode));
}

void bluetoothAuthComplete(boolean success)
{
  Serial.println(success ? "Bluetooth pairing authenticated." : "Bluetooth pairing rejected.");
}

void processBluetoothPairingConfirmation()
{
  if (!pairingConfirmationPending)
  {
    return;
  }

  if (millis() - pairingConfirmationStartedAt >= 25000)
  {
    pairingConfirmationPending = false;
    SerialBT.confirmReply(false);
    Serial.println("\nBluetooth pairing timed out and was rejected.");
    return;
  }

  while (Serial.available())
  {
    const int answer = Serial.read();
    if (answer == '\r' || answer == '\n')
    {
      continue;
    }

    pairingConfirmationPending = false;
    const bool accepted = answer == 'Y' || answer == 'y';
    SerialBT.confirmReply(accepted);
    Serial.println(accepted
        ? "\nBluetooth pairing confirmation accepted."
        : "\nBluetooth pairing rejected; enter Y only when the codes match.");
    return;
  }
}

unsigned long lastReport = 0;
unsigned long lastWiFiRetry = 0;
bool wifiInfoPrinted = false;
bool timeSyncStarted = false;


// =====================================================
// MAX30102
// =====================================================

MAX30105 optical;

const int PPG_SAMPLES = PpgReadingState::kWindowSamples;
const int PPG_STEP = PpgReadingState::kStepSamples;
static_assert(FreqS == 25, "MAX30102 setup must match the algorithm's 25 Hz input");

uint32_t irBuffer[PPG_SAMPLES];
uint32_t redBuffer[PPG_SAMPLES];

PpgReadingState ppg;
PpgFifoReader ppgFifo;

class CheckedPpgBus
{
 public:
  bool read(uint8_t reg, uint8_t *buffer, uint8_t length)
  {
    Wire.beginTransmission(MAX30102_ADDRESS);
    const bool queued = Wire.write(reg) == 1;
    const uint8_t error = Wire.endTransmission(false);
    if (!queued || error != 0) return false;
    const uint8_t received = Wire.requestFrom(
      static_cast<uint8_t>(MAX30102_ADDRESS), length, true);
    if (received != length || Wire.available() != length)
    {
      while (Wire.available()) Wire.read();
      return false;
    }
    for (uint8_t i = 0; i < length; ++i)
    {
      const int value = Wire.read();
      if (value < 0) return false;
      buffer[i] = static_cast<uint8_t>(value);
    }
    return true;
  }

  bool write(uint8_t reg, uint8_t value)
  {
    Wire.beginTransmission(MAX30102_ADDRESS);
    const bool regQueued = Wire.write(reg) == 1;
    const bool valueQueued = Wire.write(value) == 1;
    const uint8_t error = Wire.endTransmission();
    return regQueued && valueQueued && error == 0;
  }
};

CheckedPpgBus checkedPpgBus;

uint32_t latestIR = 0;
uint32_t latestRed = 0;

bool maxOnline = false;

int32_t heartRate = 0;
int32_t spo2 = 0;

const uint32_t FINGER_THRESHOLD = 50000;



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

  ppg.initialize(maxOnline);


  if (!maxOnline)
  {
    Serial.println("MAX30102 not found");

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


  // Read verified complete hardware FIFO records directly. The SparkFun
  // software ring/check() does not report I2C errors and must not be used to
  // infer a physical sample gap from an unchecked pointer/backlog value.
  ppg.expire(millis());
  const PpgFifoBatch batch = ppgFifo.poll(checkedPpgBus);
  if (batch.gapBefore)
  {
    ppg.noteSampleLoss(batch.confirmedLostMinimum);
  }


  for (uint8_t sampleIndex = 0; sampleIndex < batch.count; ++sampleIndex)
  {

    uint32_t red =
      batch.samples[sampleIndex].red;

    uint32_t ir =
      batch.samples[sampleIndex].ir;


    latestRed = red;
    latestIR = ir;


    // -------------------------------------------------
    // Finger detection
    // -------------------------------------------------

    const uint32_t sampledAt = millis();
    ppg.sample(sampledAt, ir > FINGER_THRESHOLD);


    if (!ppg.fingerDetected())
    {
      heartRate = 0;
      spo2 = 0;
      continue;
    }


    // -------------------------------------------------
    // Finger detected
    // -------------------------------------------------

    // Store sample

    irBuffer[ppg.windowSamples() - 1] = ir;

    redBuffer[ppg.windowSamples() - 1] = red;


    // -------------------------------------------------
    // Wait until 100 samples collected
    // -------------------------------------------------

    if (ppg.ready())
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

      const bool hrValid =
        heartRateValidFlag &&
        heartRate >= 30 &&
        heartRate <= 220;


      // -------------------------------------------------
      // Validate SpO2
      // -------------------------------------------------

      const bool spo2Valid =
        spo2ValidFlag &&
        spo2 >= 70 &&
        spo2 <= 100;


      // -------------------------------------------------
      // Sensor status
      // -------------------------------------------------

      ppg.calculated(millis(), hrValid, spo2Valid);


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


    }
  }
  if (batch.gapAfter)
  {
    // Earlier complete samples precede this irrecoverable transfer, so clear
    // after processing them rather than publishing a result across the gap.
    ppg.noteSampleLoss(0);
  }
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

  // A reporting delay must not make an old valid result look current.
  const uint32_t opticalNow = millis();
  ppg.expire(opticalNow);


  max["i2c_address"] = "0x57";

  max["status"] = ppg.statusName();
  max["online"] = maxOnline;

  max["finger_detected"] =
    ppg.fingerDetected();


  max["heart_rate_valid"] =
    ppg.heartRateValid();

  max["spo2_valid"] =
    ppg.oxygenValid();


  if (ppg.heartRateValid())
  {
    max["heart_rate_bpm"] =
      heartRate;
  }
  else
  {
    max["heart_rate_bpm"] =
      nullptr;
  }


  if (ppg.oxygenValid())
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
    ppg.windowSamples();

  max["window_target"] =
    PPG_SAMPLES;

  max["effective_sample_hz"] = 25;
  max["sample_count"] = ppg.sampleCount();
  max["calculation_count"] = ppg.calculationCount();
  max["sample_loss_events"] = ppg.lossEvents();
  max["samples_dropped"] = ppg.droppedSamples();
  max["samples_dropped_is_minimum"] = true;
  const PpgFifoDiagnostics &fifoDiagnostics = ppgFifo.diagnostics();
  max["fifo_reader"] = "checked_direct";
  max["pointer_read_errors"] = fifoDiagnostics.pointerReadErrors;
  max["pointer_unused_bits_observations"] = fifoDiagnostics.pointerUnusedBitsObservations;
  max["fifo_read_errors"] = fifoDiagnostics.sampleReadErrors;
  max["adc_unused_bits_observations"] = fifoDiagnostics.adcUnusedBitsObservations;
  max["fifo_restore_attempts"] = fifoDiagnostics.restoreAttempts;
  max["fifo_restore_failures"] = fifoDiagnostics.restoreFailures;
  max["fifo_restore_successes"] = fifoDiagnostics.restoreSuccesses;
  max["unrecoverable_read_events"] = fifoDiagnostics.unrecoverableReadEvents;
  max["hardware_overflow_events"] = fifoDiagnostics.hardwareOverflowEvents;
  max["hardware_lost_samples_minimum"] = fifoDiagnostics.confirmedLostMinimum;
  max["overflow_recovery_attempts"] = fifoDiagnostics.overflowRecoveryAttempts;
  max["overflow_recovery_failures"] = fifoDiagnostics.overflowRecoveryFailures;
  max["overflow_recovery_successes"] = fifoDiagnostics.overflowRecoverySuccesses;
  max["overflow_discarded_samples_minimum"] = fifoDiagnostics.overflowDiscardedSamplesMinimum;
  if (ppg.hasSample()) max["sample_age_ms"] = ppg.sampleAge(opticalNow);
  else max["sample_age_ms"] = nullptr;
  if (ppg.hasCalculation()) max["result_age_ms"] = ppg.resultAge(opticalNow);
  else max["result_age_ms"] = nullptr;


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

  if (!SerialBT.hasClient())
  {
    Serial.println("Bluetooth SPP skipped: no phone is connected.");
    return;
  }

  const size_t jsonBytes = SerialBT.print(json);
  const size_t newlineBytes = SerialBT.write('\n');
  if (jsonBytes != json.length() || newlineBytes != 1)
  {
    Serial.println("Bluetooth SPP write was incomplete; the phone should discard this line.");
    return;
  }

  Serial.printf("Bluetooth SPP -> sent %u-byte sensor record.\n",
                static_cast<unsigned int>(jsonBytes));
}


// =====================================================
// SETUP
// =====================================================

void setup()
{
  // Keep the 1 Hz JSON report from blocking the 25 Hz optical acquisition.
  // ESP32 UART TX is synchronous unless a TX ring is configured before begin.
  Serial.setTxBufferSize(4096);
  Serial.begin(115200);

  delay(1000);

  SerialBT.enableSSP(true, true);
  SerialBT.onConfirmRequest(confirmBluetoothPairing);
  SerialBT.onAuthComplete(bluetoothAuthComplete);
  if (!SerialBT.begin(BT_DEVICE_NAME))
  {
    Serial.println("Bluetooth SPP failed to start; check that this is a classic ESP32.");
  }
  else
  {
    Serial.printf("Bluetooth SPP ready: %s\n", BT_DEVICE_NAME);
    Serial.printf("Bluetooth MAC: %s\n", SerialBT.getBtAddressString().c_str());
    Serial.println("Pair from Android Settings; compare the numeric code on both devices.");
  }

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
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
  processBluetoothPairingConfirmation();

  if (WiFi.status() == WL_CONNECTED)
  {
    if (!wifiInfoPrinted)
    {
      wifiInfoPrinted = true;
      Serial.print("Wi-Fi connected. ESP32 IP: ");
      Serial.println(WiFi.localIP());
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
