#!/bin/bash
echo "Starting EverStatus application..."
java -jar target/activetrack-1.0.0.jar > output.log 2>&1
echo "Application finished with exit code $?"
