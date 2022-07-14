#include "defines.h"
#include "arduino_secrets.h"

#include <SPI.h>
#include <WiFiNINA_Generic.h>
#include <WiFiUdp_Generic.h>
#include <MDNS_Generic.h>

// number of milliseconds to wait for a connection after calling
// the function to connect
#define WIFI_CONNECTION_WAIT 1000;

#define STATE_DISCONNECTED_OFF  00;
#define STATE_DISCONNECTED_ON   01;
#define STATE_CONNECTED_OFF     10;
#define STATE_CONNECTED_ON      11;

String hostname = "jmc-coffee-pot;

char wifiNetworkSsid[] = SECRET_SSID;
char wifiPassword[] = SECRET_PASS;

// the Wifi radio's status
int wifiStatus = WL_IDLE_STATUS;

int appStatus  = STATE_DISCONNECTED_OFF;

MDNS mdns(udp);
WiFiUDP udp;
WiFiServer server(80);

void setup() {
  // put your setup code here, to run once:
  Serial.begin(9600);
}

void loop() {
  /*
   * This switch implements a state machine. It will attempt to do
   * one thing in each loop, and it will adjust the state if necessary
   * on the way out.
   */
  if (WiFi.status() != WL_CONNECTED) {
    tryConnect();
    delay(1000);

    // if we just connected, try NSD registration
    if (WiFi.status() == WL_CONNECTED) {
      server.begin();
      mdns.begin(WiFi.localIP(), hostname.c_str());
      mdns.addServiceRecord("JCHome._coffeepot", 80, MDNSServiceTCP);
    }
    return;
  }

  mdns.run();
  

}

void tryConnect() {
  WiFi.begin(wifiNetworkSsid, wifiPassword);
}
