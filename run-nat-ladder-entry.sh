dp0=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"/"
echo -ne '\033]2;Entry Relay\007'
CENTRAL_RELAY_HOST="kevinj.in"
CENTRAL_RELAY_PORT=3425
IDENTIFIER="test"
PASSWORD="test"
java -classpath $dp0"nat-ladder-common/bin:"$dp0"nat-ladder-client/bin" -ea in.kevinj.natladder.boundaryrelay.NatLadderEntryNode $CENTRAL_RELAY_HOST $CENTRAL_RELAY_PORT $IDENTIFIER $PASSWORD
#read -n1 -rsp $'Press any key to continue . . . \n'
