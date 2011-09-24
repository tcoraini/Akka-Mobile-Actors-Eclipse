#!/bin/bash

node=node_2
if [ $# -eq 1 ]; then
    node=$1
fi

ant startTheater -Dnode_name=$node
