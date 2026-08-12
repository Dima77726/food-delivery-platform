# Многоступенчатая сборка: инструменты сборки не должны попасть в рантайм-образ.
# Maven и JDK весят сотни мегабайт и содержат компилятор, которому в проде делать нечего.

FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Сначала только описания сборки. Слой с зависимостями пересобирается лишь при правке pom,
# а не при каждом изменении исходников — иначе Maven тянул бы полтерабайта Spring Boot
# на каждый коммит.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY service/pom.xml service/
COPY client/pom.xml client/

RUN ./mvnw -B --no-transfer-progress -q dependency:go-offline

# Спецификация нужна до исходников: из неё генерируются интерфейсы, которые они реализуют.
COPY openapi/ openapi/
COPY service/src/ service/src/

# Тесты здесь не гоняются намеренно: интеграционные требуют Docker внутри Docker.
# Их место в CI, до сборки образа.
RUN ./mvnw -B --no-transfer-progress -q -pl service -am package -DskipTests


FROM eclipse-temurin:21-jre-alpine AS runtime

# Непривилегированный пользователь. Процесс в контейнере по умолчанию работает от root,
# и при выходе за пределы контейнера это сразу максимальные права на хосте.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

COPY --from=build --chown=app:app /build/service/target/*.jar app.jar

USER app

EXPOSE 8080

# Форма exec, а не shell: процесс Java становится PID 1 и получает SIGTERM напрямую.
# В shell-форме сигнал уходит оболочке, приложение о нём не узнаёт и его добивает SIGKILL
# по таймауту — с оборванными транзакциями.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
