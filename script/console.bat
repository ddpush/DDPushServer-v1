@echo off
set "DIR=%~dp0%"

java -classpath %DIR%lib\ddpush-1.0.02.jar org.ddpush.im.v1.node.IMServerConsole %1