#include "defines.h"
#include "arduino_secrets.h"

#include <SPI.h>
#include <WiFiNINA_Generic.h>
#include <WiFiUdp_Generic.h>
#include <MDNS_Generic.h>
#include <arduino-timer.h>


// number of milliseconds to wait for a connection after calling
// the function to connect
#define WIFI_CONNECTION_WAIT 1000

#define POT_RELAY_PIN 10

#define STATE_OFF           0
#define STATE_BREWING       1
#define STATE_DELAYING      2
#define STATE_WARMING       3
#define STATE_FULL_SCHEDULE 4
int potState = STATE_OFF;

char wifiNetworkSsid[] = SECRET_SSID;
char wifiPassword[] = SECRET_PASS;

const char serviceName[] = "Johns Coffee Pot._johnscoffeepot";
String hostname = "johns-coffee-pot";

WiFiUDP udp;
MDNS mdns(udp);
WiFiServer server(80);
WiFiClient client;
Timer<1> timer;

void setup() {
  // put your setup code here, to run once:
  pinMode(POT_RELAY_PIN, OUTPUT);
  Serial.begin(9600);
}

void loop() {
  // maintenance tasks
  mdns.run();
  timer.tick();

  /*
   * The following code implements a state machine. It will attempt to do
   * one thing in each loop, and it will adjust the state if appropriate.
   */
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Not connected to WiFi");
    tryConnect();
    delay(WIFI_CONNECTION_WAIT);
    
    // if we just connected, try NSD registration
    if (WiFi.status() == WL_CONNECTED) {
      Serial.print("Now connected, IP ");
      Serial.println(WiFi.localIP());
      server.begin();
      Serial.println("Server started");
      mdns.begin(WiFi.localIP(), hostname.c_str());
      mdns.addServiceRecord(serviceName, 80, MDNSServiceTCP);
      Serial.println("Service registered");
    }
    return;
  }

  // Now, the WiFi is connected and the server is listening.

  while(client = server.available()) {
    Serial.println("Processing client data");
    processClientData(client);
  }
}

/*
 * Protocol:
 * Messages are C-style strings: null-terminated.
 * First character denotes a command:
 * 'B'    Start brewing now. Format: ['B']
 * 'T'    Stop brewing now. Format: ['T']
 * 'D'    Delay n seconds to brew. Format:
 *        ['D',{1},{2}] where [{1},{2}] is read as a uint16_t, {1} is most significant byte.
 * 'W'    Turn on a warming timer: aka, delay n seconds to stop brewing.
 *        Similar to the format for 'D'
 *        ['W',{1},{2}] where [{1},{2}] is read as a uint16_t
 * 'C'    Turn on a brewing timer, then a warming timer. Format: ['W',{1},{2},{3},{4}]
 *        Where [{1},{2}] is the brewing timer starting now, and [{3},{4}] is the warming timer
 *        starting not now but once the brew delay expires and brewing has started.
 * 'M'    Request pot status. Format: ['M'].
 *        On this command, write back to the client [{X}[,{1},{2}]]
 *        where 'R' is the response start character, and inclusion of [,{1},{2}] depends on case.
 *        {X} is one of:
 *          'B' brewing now (no 3rd and 4th bytes)
 *          'T' off (no 3rd and 4th bytes)
 *          'D' pot is off but a delay to start brewing is counting down and has
 *              [{1},{2}] read as a uint16_t interpreted as "seconds left till brewing"
 *          'W'  pot is brewing but will turn off in n seconds, with n read as in case for 'D'.
 */
void processClientData(WiFiClient client) {
//  while(client.available()) {
//    Serial.print("available ");
//    Serial.println(client.available());
//    Serial.println(client.read());
//  }
//
//  return;

  Serial.print("available ");
  Serial.println(client.available());
  
  if (client.available()) {
    char c1 = client.peek();
    Serial.println(c1);
    switch(c1) {
      case 'B':
        client.read(); // throw away
        transition(STATE_BREWING);
        client.write('K');
        break;
      case 'T':
        client.read(); // throw away
        transition(STATE_OFF);
        client.write('K');
        break;
      case 'D':
      case 'W':
      {
        if (client.available() < 3) {
          // this isn't a complete message. quit and try again.
          break;
        }
        
        client.read(); // throw away the message identifier
        char byte1 = client.read();
        char byte2 = client.read();
        Serial.print("byte1 ");
        Serial.println(byte1);
        Serial.print("byte2 ");
        Serial.println(byte2);
        uint16_t timerSeconds = parseSecondsFromNetworkData(byte1, byte2);

        transition(c1 == 'D' ? STATE_DELAYING : STATE_WARMING, timerSeconds);
        client.write('K');

        break;
      }
      case 'C':
      {
        if (client.available() < 5) {
          // this isn't a complete message. quit and try again.
          break;
        }

        client.read(); // throw away the message identifier
        uint16_t firstTimerSeconds = parseSecondsFromNetworkData(client.read(), client.read());
        uint16_t secondTimerSeconds = parseSecondsFromNetworkData(client.read(), client.read());
        
        transition(STATE_FULL_SCHEDULE, firstTimerSeconds, secondTimerSeconds);
        client.write('K');

        break;
      }
      case 'M':
      {
        client.read();
        char stateMessage = potState == STATE_OFF ? 'T' :
                            potState == STATE_BREWING ? 'B' :
                            potState == STATE_DELAYING ? 'D' : 'W';
        client.write(stateMessage);
        if (potState == STATE_DELAYING || potState == STATE_WARMING) {
          unsigned long ticksUntil = timer.ticks() / 1000;
          
          client.write(bigByteOfSeconds(ticksUntil));
          client.write(smallByteOfSeconds(ticksUntil));
        }
        break;
      }
      default:
        client.read();
        break;
    }
  }
}

uint16_t parseSecondsFromNetworkData(char a, char b) {
  uint16_t r = a;
  r = (r << 8) | b;
  Serial.print("parsedData: ");
  Serial.println(r);
  return r;
}

/*
 * bigByteOfSeconds and smallByteOfSeconds accept the number of ticks in milliseconds,
 * from the timer, until the next event.
 */
char bigByteOfSeconds(unsigned long ticksUntil) {
  return (ticksUntil & 0xFF00) >> 8;
}

/*
 * bigByteOfSeconds and smallByteOfSeconds accept the number of ticks in milliseconds,
 * from the timer, until the next event.
 */
char smallByteOfSeconds(unsigned long ticksUntil) {
  return ticksUntil & 0xFF;
}

void transition(int newState) {
  transition(newState, 0);
}

void transition(int newState, uint16_t timerSeconds) {
  if (newState == STATE_DELAYING) {
    transition(newState, timerSeconds, 0);
    return;
  } else if (newState == STATE_WARMING) {
    transition(newState, 0, timerSeconds);
    return;
  }

  transition(newState, 0, 0);
}

void transition(int newState, uint16_t brewTimerSeconds, uint16_t warmTimerSeconds) {  
  Serial.print("Switching to state: ");
  
  if (potState == STATE_DELAYING || potState == STATE_WARMING) {
    timer.cancel();
  }

  switch (newState) {
    case STATE_BREWING:
      Serial.println("Brewing");
      turnPotOn();
      break;

    case STATE_OFF:
      Serial.println("Off");
      turnPotOff();
      break;

    case STATE_DELAYING:
      Serial.print("Delaying, timer ");
      Serial.println(brewTimerSeconds);
      turnPotOff();
      // *1000 because timer.in expects milliseconds.
      timer.in(brewTimerSeconds*1000, [](void*) -> bool {
        transition(STATE_BREWING);
        return false;
      });
      break;
    case STATE_WARMING:
      Serial.print("Warming, timer ");
      Serial.println(warmTimerSeconds);
      turnPotOn();
      // *1000 because timer.in expects milliseconds.
      timer.in(warmTimerSeconds*1000, [](void*) -> bool {
        transition(STATE_OFF);
        return false;
      });
      break;
    case STATE_FULL_SCHEDULE:
      Serial.print("Fullschedule, timers ");
      Serial.print(brewTimerSeconds);
      Serial.print(", ");
      Serial.print(warmTimerSeconds);
      timer.in(brewTimerSeconds*1000, [](void* _warmTimerSeconds) -> bool {
        transition(STATE_WARMING, *((uint16_t*)_warmTimerSeconds));
        return false;
      }, (void *)warmTimerSeconds);
  }
  
  potState = newState;
}

void turnPotOn() {
  digitalWrite(POT_RELAY_PIN, HIGH);
}

void turnPotOff() {
  digitalWrite(POT_RELAY_PIN, LOW);
}

void tryConnect() {
  WiFi.begin(wifiNetworkSsid, wifiPassword);
}
