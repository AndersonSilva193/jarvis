# Etapa 1: compila o projeto com Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Etapa 2: imagem final só com o Java e o .jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 jarvis
# O cache de voz precisa existir COM dono certo antes do USER: o Docker copia essa
# propriedade ao criar o volume nomeado, e sem isso ele nasceria do root e o Jarvis
# (uid 1001) não conseguiria gravar nada.
RUN mkdir -p /data/voz && chown jarvis:jarvis /data/voz
COPY --from=build /app/target/jarvis.jar app.jar
USER jarvis
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
