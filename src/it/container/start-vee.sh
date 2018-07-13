#!/bin/bash

DEFAULT_NET_IP=`ifconfig eth0 | awk '/inet addr/ {gsub("addr:", "", $2); print $2}'`
WAVES_NET_IP=`ifconfig eth1 | awk '/inet addr/ {gsub("addr:", "", $2); print $2}'`

echo Default: $DEFAULT_NET_IP
echo Waves: $WAVES_NET_IP
echo Options: $WAVES_OPTS

#java -Dwaves.network.declared-address=$WAVES_NET_IP:$WAVES_PORT $WAVES_OPTS -jar /opt/vee/vee.jar /opt/vee/template.conf
java -Dwaves.network.declared-address=$DEFAULT_NET_IP:6863 $WAVES_OPTS -jar /opt/vee/vee.jar /opt/vee/template.conf
