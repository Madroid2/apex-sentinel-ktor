FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :app:buildFatJar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 sentinel
WORKDIR /app
COPY --from=build /workspace/app/build/libs/apex-sentinel.jar /app/apex-sentinel.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/apex-sentinel.jar"]
