# Reproduces the Linux x86-64 build outside CI, which is useful when you develop on Windows
# or macOS but deploy to Linux.
#
#   docker build -t lingua-rs-jni .                       # build and run the test suite
#   docker build --target artifacts --output dist .       # write the JARs to ./dist
#
FROM maven:3.9.9-eclipse-temurin-21 AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl build-essential \
    && rm -rf /var/lib/apt/lists/* \
    && curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
       | sh -s -- -y --profile minimal --no-modify-path

ENV PATH="/root/.cargo/bin:${PATH}"
WORKDIR /workspace

# Resolve Maven dependencies before the sources land so editing code does not re-download
# them. Best effort: a failure here only costs cache warmth.
COPY pom.xml ./
RUN mvn -B -ntp -DskipNativeBuild=true dependency:go-offline || true

COPY native ./native
COPY src ./src
COPY LICENSE THIRD_PARTY_NOTICES.md ./
RUN mvn -B -ntp clean verify

# Nothing but the JARs, so `--output` writes exactly those to the host.
FROM scratch AS artifacts
COPY --from=build /workspace/target/*.jar /

FROM build AS test
CMD ["mvn", "-B", "-ntp", "test"]
