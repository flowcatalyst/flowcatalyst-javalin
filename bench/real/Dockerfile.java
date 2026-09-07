# The Java server as deployed: the exec jar on a JRE, nothing else.
FROM eclipse-temurin:25-jre-alpine
COPY flowcatalyst-server-exec.jar /app/server.jar
ENTRYPOINT ["java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/server.jar"]
