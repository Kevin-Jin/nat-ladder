@ECHO OFF
@TITLE Entry Relay
SET CENTRAL_RELAY_HOST=kevinj.in
SET CENTRAL_RELAY_PORT=3425
SET IDENTIFIER=test
SET PASSWORD=test
java -classpath "%~dp0nat-ladder-common\bin;%~dp0nat-ladder-client\bin" -ea in.kevinj.natladder.boundaryrelay.NatLadderEntryNode %CENTRAL_RELAY_HOST% %CENTRAL_RELAY_PORT% %IDENTIFIER% %PASSWORD%
pause
