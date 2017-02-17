#!/bin/sh
dp0=$( cd "$( dirname "$(readlink -f "$0")" )" && pwd )"/"

rm -rf $dp0"../nat-ladder-common/bin"
mkdir $dp0"../nat-ladder-common/bin"
javac -d $dp0"../nat-ladder-common/bin" $(find $dp0"../nat-ladder-common/src/" -name "*.java")

rm -rf $dp0"../nat-ladder-central/bin"
mkdir $dp0"../nat-ladder-central/bin"
javac -classpath $dp0"../nat-ladder-common/bin" -d $dp0"../nat-ladder-central/bin" $(find $dp0"../nat-ladder-central/src/" -name "*.java")

rm -rf $dp0"../nat-ladder-client/bin"
mkdir $dp0"../nat-ladder-client/bin"
javac -classpath $dp0"../nat-ladder-common/bin" -d $dp0"../nat-ladder-client/bin" $(find $dp0"../nat-ladder-client/src/" -name "*.java")
