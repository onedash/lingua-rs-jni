# Reproduces the Alpine Linux x86-64 build outside CI.
#
#   docker build -t lingua-rs-jni .                       # build and run the test suite
#   docker build --target artifacts --output dist .       # write the JARs to ./dist
#
FROM maven:3.9.16-eclipse-temurin-21-alpine AS build

RUN apk add --no-cache build-base file pax-utils \
    && curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
       | sh -s -- -y --profile minimal --component clippy --component rustfmt --no-modify-path

ENV PATH="/root/.cargo/bin:${PATH}"
WORKDIR /workspace

# Resolve Maven dependencies before the sources land so editing code does not re-download
# them. Best effort: a failure here only costs cache warmth.
COPY pom.xml ./
RUN mvn -B -ntp -DskipNativeBuild=true dependency:go-offline || true

COPY native ./native
COPY src ./src
COPY LICENSE THIRD_PARTY_NOTICES.md ./
RUN cd native \
    && cargo fmt --check \
    && cargo clippy --locked --all-targets -- -D warnings \
    && cargo test --release --locked
RUN mvn -B -ntp clean verify
RUN library=target/generated-resources/native/linux-x86_64-musl/liblingua_rs_jni.so \
    && file "$library" \
    && needed="$(scanelf --nobanner --format '%n' "$library")" \
    && echo "Runtime dependencies: $needed" \
    && [ "$needed" = libc.musl-x86_64.so.1 ]

# Prove that the library loads in BackendMono's unchanged runtime image, without build-base.
FROM eclipse-temurin:21-jre-alpine AS runtime-test
COPY --from=build /workspace/target/classes /app/classes
COPY --from=build /workspace/target/test-classes /app/test-classes
RUN java -cp /app/classes:/app/test-classes io.github.onedash.linguarsjni.AlpineRuntimeSmoke

# Raw native output consumed by the release workflow.
FROM scratch AS native-artifact
COPY --from=runtime-test /app/classes/native /native

# Nothing but the JARs, so `--output` writes exactly those to the host.
FROM scratch AS artifacts
COPY --from=build /workspace/target/*.jar /

FROM build AS test
CMD ["mvn", "-B", "-ntp", "test"]
