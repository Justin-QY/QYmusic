# syntax=docker/dockerfile:1
#
# Music Killer 后端镜像（多阶段构建）
#
# 使用方式（在仓库根目录或 deploy 目录下，见 docker-compose.yml）：
#   docker compose -f deploy/docker-compose.yml up -d --build
#
# 第一阶段用 Maven 在容器内从源码构建 jar，第二阶段只保留运行环境，
# 因此不需要在服务器上安装 JDK / Maven，也不需要提前准备 jar 包。

# ---------------------------------------------------------------- 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /src

# 先只拷贝 pom，单独下载依赖，利用 Docker 层缓存加速后续重建
COPY music-killer-java/pom.xml ./
COPY music-killer-java/music-killer-common/pom.xml music-killer-common/
COPY music-killer-java/music-killer-pojo/pom.xml music-killer-pojo/
COPY music-killer-java/music-killer-server/pom.xml music-killer-server/
RUN mvn -B -Pprod -DskipTests dependency:go-offline || true

# 再拷贝源码并打包
COPY music-killer-java/ ./
RUN mvn -B -Pprod -DskipTests package \
    && cp music-killer-server/target/music-killer-server-*.jar /src/app.jar

# ---------------------------------------------------------------- 运行阶段
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

WORKDIR /app

COPY --from=builder /src/app.jar /app/app.jar
COPY deploy/application-prod.yml /app/config/application-prod.yml

EXPOSE 8002

# 容器内存上限按 1G 设置（音源脚本引擎需要额外堆），可通过 docker-compose 的 JAVA_OPTS 覆盖
ENV JAVA_OPTS="-Xms256m -Xmx1024m -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=prod"]
