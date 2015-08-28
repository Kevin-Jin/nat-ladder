@ECHO OFF
@TITLE Exit Relay
SET CENTRAL_RELAY_HOST=kevinj.in
SET CENTRAL_RELAY_PORT=3425
SET IDENTIFIER=test
SET PASSWORD=test
SET TERMINUS_HOST=localhost
SET TERMINUS_PORT=8080
java -classpath "%~dp0nat-ladder-common\bin;%~dp0nat-ladder-client\bin" -ea -Djava.util.logging.config.file="%~dp0logging.properties" in.kevinj.natladder.exitnode.NatLadderExitNode %CENTRAL_RELAY_HOST% %CENTRAL_RELAY_PORT% %IDENTIFIER% %PASSWORD% %TERMINUS_HOST% %TERMINUS_PORT%
pause
