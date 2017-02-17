#!/bin/sh
dp0=$( cd "$( dirname "$(readlink -f "$0")" )" && pwd )"/"
case $1 in
  central)
    screen -A -m -d -S $1 $dp0../run-nat-ladder-central.sh
    ;;
  entry)
    screen -A -m -d -S $1 $dp0../run-nat-ladder-entry.sh
    ;;
  exit)
    screen -A -m -d -S $1 $dp0../run-nat-ladder-exit.sh
    ;;
  *)
    echo "Invalid client type"
    ;;
esac
