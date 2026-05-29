#!/bin/sh
# Gradle wrapper script
APP_HOME=$( cd "${0%[/\\]*}" > /dev/null; pwd -P )
JAVACMD="java"
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi
exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
