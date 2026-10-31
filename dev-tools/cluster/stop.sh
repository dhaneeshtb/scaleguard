#!/bin/bash
# stops every test node started from this folder
pkill -f "cluster/home[0-9]" ; sleep 1
