#!/bin/sh
dp0=$( cd "$( dirname "$(readlink -f "$0")" )" && pwd )"/"
printf '\033]2;Central Relay\007'
CENTRAL_RELAY_HOST="0.0.0.0"
CENTRAL_RELAY_PORT=13425
java -classpath $dp0"nat-ladder-common/bin:"$dp0"nat-ladder-central/bin" -ea -Djava.util.logging.config.file=$dp0"logging.properties" in.kevinj.natladder.centralrelay.NatLadderCentralRelay $CENTRAL_RELAY_HOST $CENTRAL_RELAY_PORT
#read -n1 -rsp $'Press any key to continue . . . \n'
