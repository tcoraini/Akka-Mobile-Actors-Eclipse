#!/bin/bash

itinerary=itinerary.txt
if [ $# -gt 1 ]; then
   itinerary=$2
fi

ant travelTime -Dnbytes=$1 -Ditinerary=$itinerary
