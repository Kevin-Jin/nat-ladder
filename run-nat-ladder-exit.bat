@ECHO OFF
@TITLE Exit Relay
SET CENTRAL_RELAY_HOST=kevinj.in
SET CENTRAL_RELAY_PORT=3425
SET IDENTIFIER=test
SET PASSWORD=test
SET TERMINUS_HOST=localhost
SET TERMINUS_PORT=8080
java -classpath "%~dp0nat-ladder-common\bin;%~dp0nat-ladder-client\bin" -ea in.kevinj.natladder.boundaryrelay.NatLadderExitNode %CENTRAL_RELAY_HOST% %CENTRAL_RELAY_PORT% %IDENTIFIER% %PASSWORD% %TERMINUS_HOST% %TERMINUS_PORT%
pause
