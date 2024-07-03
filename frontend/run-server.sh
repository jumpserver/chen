#! /bin/bash

cd ..

mvn clean package -DskipTests

java -Dspring.profiles.active=dev -jar backend/web/target/web-0.0.1.jar

