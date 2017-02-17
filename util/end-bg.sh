#!/bin/sh
dp0=$( cd "$( dirname "$(readlink -f "$0")" )" && pwd )"/"
screen -S $1 -X quit
