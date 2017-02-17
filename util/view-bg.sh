#!/bin/sh
dp0=$( cd "$( dirname "$(readlink -f "$0")" )" && pwd )"/"
screen -d -r $1
