(ns adsb.fixtures
  (:require [adsb.ingest.coerce :as coerce]))

(def ups-2717-raw
  {:hex       "abc0e4"
   :type      "adsb_icao"
   :flight    "UPS2717 "
   :alt_baro  34775
   :alt_geom  36925
   :gs        450.5
   :track     97.14
   :baro_rate -960
   :squawk    "6040"
   :emergency "none"
   :category  "A5"
   :lat       27.961166
   :lon       -83.975953
   :nav_qnh   1013.6
   :mlat      []
   :tisb      []
   :messages  1848
   :seen      0.4
   :rssi      -28.3})

(def ups-2717 (coerce/->aircraft ups-2717-raw))

(def on-the-ground-raw
  {:hex       "a1d645"
   :type      "adsb_icao"
   :flight    "N2173A  "
   :alt_baro  "ground"
   :gs        12.5
   :track     81.44
   :squawk    "1200"
   :emergency "none"
   :category  "A1"
   :lat       27.645950
   :lon       -82.497789
   :mlat      []
   :tisb      []
   :messages  1186
   :seen      0.1
   :rssi      -23.5})

(def on-the-ground (coerce/->aircraft on-the-ground-raw))

(def never-positioned-raw
  {:hex      "a10202"
   :type     "mode_s"
   :alt_baro 33000
   :alt_geom 35125
   :mlat     []
   :tisb     []
   :messages 74
   :seen     3.5
   :rssi     -29.7})

(def never-positioned (coerce/->aircraft never-positioned-raw))

(def squawking-7700-raw
  {:hex       "a35a92"
   :type      "adsb_icao"
   :flight    "DAL1275 "
   :alt_baro  10025
   :gs        311.1
   :track     150.96
   :baro_rate -1152
   :squawk    "7700"
   :emergency "general"
   :category  "A3"
   :lat       28.364136
   :lon       -82.968063
   :nav_qnh   1020.0
   :mlat      []
   :tisb      []
   :messages  149
   :seen      0.1
   :rssi      -26.5})

(def squawking-7700 (coerce/->aircraft squawking-7700-raw))

(def long-silent-raw
  {:hex       "a2b3a2"
   :type      "adsb_icao"
   :flight    "SWA2857 "
   :alt_baro  26600
   :gs        425.0
   :track     270.81
   :baro_rate 2176
   :squawk    "1034"
   :emergency "none"
   :category  "A3"
   :lat       28.432012
   :lon       -82.324434
   :nav_qnh   1013.6
   :mlat      []
   :tisb      []
   :messages  158
   :seen      300
   :rssi      -27.2})

(def long-silent (coerce/->aircraft long-silent-raw))

(def mlat-derived-raw
  {:hex       "a2ddac"
   :type      "mlat"
   :flight    "N512ML  "
   :alt_baro  34000
   :gs        458.7
   :track     324.40
   :baro_rate 64
   :category  "A3"
   :lat       28.796585
   :lon       -82.682208
   :mlat      ["lat" "lon" "gs" "track" "baro_rate" "alt_baro"]
   :tisb      []
   :messages  4585
   :seen      0.3
   :rssi      -29.9})

(def mlat-derived (coerce/->aircraft mlat-derived-raw))

(def all-raw
  [ups-2717-raw
   on-the-ground-raw
   never-positioned-raw
   squawking-7700-raw
   long-silent-raw
   mlat-derived-raw])

(def all
  [ups-2717
   on-the-ground
   never-positioned
   squawking-7700
   long-silent
   mlat-derived])
