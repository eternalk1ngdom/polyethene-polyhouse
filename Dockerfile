FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY polyhouse/pom.xml ./pom.xml
COPY polyhouse/src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S -g 10001 polyhouse && adduser -S -D -H -u 10001 -G polyhouse polyhouse
COPY --from=build /build/target/polyhouse-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# 低 CPU 配额下限制 JIT 的编译层级，为冷启动和首次 TLS 连接留出 CPU 时间。
ENTRYPOINT ["java", "-Xms64m", "-Xmx224m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-XX:TieredStopAtLevel=1", "-XX:MaxMetaspaceSize=128m", "-Xss512k", "-Dspring.profiles.active=prod", "-jar", "/app/app.jar"]
