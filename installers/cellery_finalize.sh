#!/usr/bin/env bash
# ----------------------------------------------------------------------------------
# Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
#
# WSO2 Inc. licenses this file to you under the Apache License,
# Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------------

CELLERY_VERSION="0.4.0"
SUPPORTED_B7A_VERSION="0.991.0"
B7A_BIN_PATH=$(which ballerina)


if [ -z ${B7A_BIN_PATH} ]; then
    echo "Cellery jar installation failed due to missing the Ballerina installation."
    exit 0
fi
B7A_VERSION=$($B7A_BIN_PATH version)

if [ ! -f ${B7A_BIN_PATH} ]; then
    B7A_BIN_PATH="/Library/Ballerina/ballerina-0.991.0/bin"
fi

if [[ "${B7A_VERSION}" =~ "${SUPPORTED_B7A_VERSION}" ]]; then
    BIN_FOLDER="/bin/ballerina"
    BALLERINA_HOME="${B7A_BIN_PATH/$BIN_FOLDER/}"
    rm -f ${BALLERINA_HOME}/bre/lib/cellery-*.jar
    cp /Library/Cellery/bre-libs/ballerina-0.991.0/bre/lib/cellery-${CELLERY_VERSION}.jar ${BALLERINA_HOME}/bre/lib/
    echo "Successfully installed the cellery in ${BALLERINA_HOME}."
else
echo "Cellery does not support the installed Ballerina version."
fi