#!/bin/bash
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/ -jar target/irc-gpt-bot*.jar $*
