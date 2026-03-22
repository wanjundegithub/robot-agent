@echo off

echo Generating API for %1...

REM 创建文件
set FILE=src\main\java\robot\agent\com\company\controller\%1Controller.java

if not exist %FILE% (
    type nul > %FILE%
)