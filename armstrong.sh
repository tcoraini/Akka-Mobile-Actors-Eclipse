#!/bin/bash

rounds=3
batch=1

if [ $# -gt 1 ]; then
    batch=$2
    if [ $# -gt 2 ]; then
	rounds=$3
    fi
fi

ant armstrong -Darguments="-t node_1 -start -n $1 -b $batch -r $rounds"

