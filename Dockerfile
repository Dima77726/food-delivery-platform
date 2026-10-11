# Один Dockerfile на все сервисы: какой собирать, задаёт аргумент MODULE.
#
#   docker build --build-arg MODULE=notification-service -t notification-service .
#
# Отдельный Dockerfile в каждом модуле дал бы несколько почти одинаковых файлов, которые
# расходятся при первой же правке: в одном забудут непривилегированного пользователя,
# в другом — форму exec у ENTRYPOINT. Сервисы разные, а способ их упаковки — один.
#
# Многоступенчатая сборка: инструменты сборки не должны попасть в рантайм-образ.
# Maven и JDK весят сотни мегабайт и содержат компилятор, которому в проде делать нечего.

# Сборочный образ — на glibc (Ubuntu), а не на Alpine, хотя рантайм остаётся Alpine.
# Стабы gRPC генерирует protoc, который Maven скачивает готовым бинарником, а эти бинарники
# собраны под glibc. На musl из Alpine protoc просто не запустится, и сборка любого сервиса,
# зависящего от libs/grpc-contracts, падала бы на генерации кода.
FROM eclipse-temurin:21-jdk AS build

ARG MODULE=api-gateway

WORKDIR /build

# Сначала только описания сборки. Слой с зависимостями пересобирается лишь при правке pom,
# а не при каждом изменении исходников. Копируются pom всех модулей реактора: без любого
# из них Maven не прочитает родительский pom со списком модулей.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/event-contracts/pom.xml libs/event-contracts/
COPY libs/grpc-contracts/pom.xml libs/grpc-contracts/
COPY insights-service/pom.xml insights-service/
COPY notification-service/pom.xml notification-service/
COPY catalog-service/pom.xml catalog-service/
COPY identity-service/pom.xml identity-service/
COPY order-service/pom.xml order-service/
COPY payment-service/pom.xml payment-service/
COPY tracking-service/pom.xml tracking-service/
COPY api-gateway/pom.xml api-gateway/
COPY client/pom.xml client/

# Зависимости только нужного модуля и того, от чего он зависит (-am). go-offline по всему
# реактору тянул бы в образ шлюза драйверы пяти баз монолита. Библиотеку контрактов
# собирать на этом шаге нечем — исходников ещё нет, — поэтому она исключена из разрешения:
# Maven искал бы её в удалённом репозитории, где её нет.
RUN ./mvnw -B --no-transfer-progress -q -pl ${MODULE} -am dependency:go-offline \
        -DexcludeGroupIds=com.dima

# Спецификация нужна каталогу до исходников: из неё генерируются интерфейсы, которые
# он реализует. Остальным модулям она не мешает.
COPY openapi/ openapi/
COPY libs/ libs/
COPY insights-service/src/ insights-service/src/
COPY notification-service/src/ notification-service/src/
COPY catalog-service/src/ catalog-service/src/
COPY identity-service/src/ identity-service/src/
COPY order-service/src/ order-service/src/
COPY payment-service/src/ payment-service/src/
COPY tracking-service/src/ tracking-service/src/
COPY api-gateway/src/ api-gateway/src/

# Тесты здесь не гоняются намеренно: интеграционные требуют Docker внутри Docker.
# Их место в CI, до сборки образа.
RUN ./mvnw -B --no-transfer-progress -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/*-exec.jar /build/app.jar


FROM eclipse-temurin:21-jre-alpine AS runtime

# Непривилегированный пользователь. Процесс в контейнере по умолчанию работает от root,
# и при выходе за пределы контейнера это сразу максимальные права на хосте.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# Именно -exec.jar: обычный jar модуля — это библиотека без встроенного запуска.
# Классификатор exec у всех сервисов, чтобы этот шаг был одинаковым для каждого.
COPY --from=build --chown=app:app /build/app.jar app.jar

USER app

EXPOSE 8080

# Форма exec, а не shell: процесс Java становится PID 1 и получает SIGTERM напрямую.
# В shell-форме сигнал уходит оболочке, приложение о нём не узнаёт и его добивает SIGKILL
# по таймауту — с оборванными транзакциями.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
