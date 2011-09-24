#!/bin/bash

node=node_1
if [ $# -eq 1 ]; then
    node=$1
fi

ant startTheater -Dnode_name=$node