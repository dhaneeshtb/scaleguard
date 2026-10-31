#!/bin/bash
# wipes the test nodes and starts a 3-node cluster on 7181-7183 (route port 7190 after setup.py)
cd "$(dirname "$0")"; ./stop.sh; rm -rf home1 home2 home3 home4 home5 home6 node-*.log
./start.sh 1 7181 7182,7183; ./start.sh 2 7182 7181,7183; ./start.sh 3 7183 7181,7182
