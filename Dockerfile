# Build context = корень репозитория. Предварительно собрать fatJar:
#   ./gradlew :application:main:fatJar
FROM mirror.gcr.io/amazoncorretto:25-alpine-jdk
COPY application/main/build/libs/*-all.jar /opt/meetup-flow-agent/app.jar
EXPOSE 8090
WORKDIR /opt/meetup-flow-agent/
ENTRYPOINT ["java", "-jar", "app.jar"]
