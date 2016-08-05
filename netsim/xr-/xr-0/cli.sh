#!/bin/sh
. ./env.sh 
${CONFD_DIR}/bin/confd_cli -u admin -J ${CONFD_CLI_FLAGS}
