dp0=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"/"
echo -ne '\033]2;Exit Relay\007'
CENTRAL_RELAY_HOST="kevinj.in"
CENTRAL_RELAY_PORT=3425
IDENTIFIER="test"
PASSWORD="test"
TERMINUS_HOST="localhost"
TERMINUS_PORT=8080
java -classpath $dp0"nat-ladder-common/bin:"$dp0"nat-ladder-client/bin" -ea -Djava.util.logging.config.file=$dp0"logging.properties" in.kevinj.natladder.exitnode.NatLadderExitNode $CENTRAL_RELAY_HOST $CENTRAL_RELAY_PORT $IDENTIFIER $PASSWORD $TERMINUS_HOST $TERMINUS_PORT
#read -n1 -rsp $'Press any key to continue . . . \n'
