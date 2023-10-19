#!/bin/bash
mvn package
jar --list -f target/irc-gpt-bot-1.5.jar | grep .png