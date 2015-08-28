@ECHO OFF
@TITLE Central Relay
SET CENTRAL_RELAY_HOST=0.0.0.0
SET CENTRAL_RELAY_PORT=3425
java -classpath "%~dp0nat-ladder-common\bin;%~dp0nat-ladder-central\bin" -ea -Djava.util.logging.config.file="%~dp0logging.properties" in.kevinj.natladder.centralrelay.NatLadderCentralRelay %CENTRAL_RELAY_HOST% %CENTRAL_RELAY_PORT%
pause
