# ===== 构建层：Maven + JDK21（Day75）=====
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src

# ⚠️ 坑修复：pom 用了 toolchains 插件，容器里没有 toolchains.xml 会构建失败
#    生成一份指向容器自身 JDK 的 toolchains.xml
RUN mkdir -p /root/.m2 && \
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n <toolchain>\n  <type>jdk</type>\n  <provider>default</provider>\n  <configuration>\n   <version>21</version>\n   <jdkHome>%s</jdkHome>\n  </configuration>\n </toolchain>\n</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml

RUN mvn -q package -DskipTests

# ===== 运行层：只有 JRE，体积小 =====
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/SpringAI-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
