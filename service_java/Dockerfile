FROM gradle:jdk18 AS builder
WORKDIR /home/gradle/src

COPY --chown=gradle:gradle . /home/gradle/src
RUN gradle build --no-daemon



FROM openjdk:18-jdk-slim
WORKDIR /app

COPY --from=builder /home/gradle/src/build/libs/*.jar /app/spring-boot-application.jar
ENTRYPOINT ["java", "-jar", "/app/spring-boot-application.jar"]
