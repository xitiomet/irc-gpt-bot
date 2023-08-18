#!/bin/bash
### BEGIN INIT INFO
# Provides:          irc-gpt-bot
# Required-Start:    $local_fs $remote_fs $network
# Required-Stop:     $local_fs $remote_fs $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: IRC chatGPT Bot
# Description:       Start the irc-gpt-bot
#  This script will start irc-gpt-bot.
### END INIT INFO

PIDFILE=/var/run/irc-gpt-bot.pid
USER=ircgptbot
GROUP=ircgptbot
CWD=/var/log/irc-gpt-bot
PROGRAM=/usr/bin/irc-gpt-bot
PROGRAM_ARGS="-h -l /var/log/irc-gpt-bot -f /etc/irc-gpt-bot/irc-gpt-bot.json"

start() {
    echo -n "Starting irc-gpt-bot Server...."
    start-stop-daemon --start --make-pidfile --pidfile $PIDFILE --chuid $USER --user $USER --group $GROUP --chdir $CWD --no-close --umask 0 --exec $PROGRAM --background -- $PROGRAM_ARGS  >> /var/log/irc-gpt-bot/proc.log 2>&1
    echo DONE
}

stop() {
    echo -n "Stopping irc-gpt-bot Server...."
    start-stop-daemon --stop --pidfile $PIDFILE --user $USER --exec $PROGRAM --retry=TERM/30/KILL/5
    echo DONE
}

status() {
    start-stop-daemon --start --test --oknodo --pidfile $PIDFILE --user $USER --exec $PROGRAM
}

case "$1" in 
    start)
       start
       ;;
    stop)
       stop
       ;;
    restart)
       stop
       start
       ;;
    status)
       status
       ;;
    *)
       echo "Usage: $0 {start|stop|status|restart}"
esac

exit 0 
