# Local Linux/F-Droid preflight; this is not the official F-Droid buildserver VM.
FROM ubuntu:24.04@sha256:2198e4c65076e9cb0b3d695219df667c0e3b0072da64ca3c2648aca17dcecaf5

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git unzip zip sudo openjdk-17-jdk-headless \
    python3 python3-venv python3-pip python3-magic apksigner \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
ENV PATH=/opt/fdroid/bin:/opt/android-sdk/cmdline-tools/19.0/bin:/opt/android-sdk/build-tools/35.0.0:${PATH}

RUN python3 -m venv /opt/fdroid && \
    /opt/fdroid/bin/pip install --no-cache-dir fdroidserver==2.4.5

# Prefer Google's CLI over the Python sdkmanager dependency of fdroidserver.
ENV PATH=/opt/android-sdk/cmdline-tools/19.0/bin:/opt/android-sdk/build-tools/35.0.0:/opt/fdroid/bin:${PATH}

# Command-line Tools 19.0: checksum from Google's repository2-1.xml.
RUN curl --fail --location --retry 3 \
      https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
      -o /tmp/sdk.zip && \
    echo '5fdcc763663eefb86a5b8879697aa6088b041e70  /tmp/sdk.zip' | sha1sum --check && \
    mkdir -p /opt/android-sdk/cmdline-tools && \
    unzip -q /tmp/sdk.zip -d /tmp/sdk && \
    mv /tmp/sdk/cmdline-tools /opt/android-sdk/cmdline-tools/19.0 && \
    rm /tmp/sdk.zip && \
    yes | sdkmanager --licenses >/dev/null && \
    sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'

# Same distribution/checksum as gradle/wrapper/gradle-wrapper.properties.
# The PyPI fdroidserver package does not ship gradlew-fdroid.
RUN curl --fail --location --retry 3 \
      https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip -o /tmp/gradle.zip && \
    echo 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6  /tmp/gradle.zip' | sha256sum --check && \
    unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip

RUN update-java-alternatives --set java-1.17.0-openjdk-amd64 && \
    useradd --create-home --uid 10001 builder && \
    echo 'builder ALL=(root) NOPASSWD: /usr/sbin/update-java-alternatives' > /etc/sudoers.d/keyra-builder && \
    mkdir /work && chown builder:builder /work && \
    chmod -R a+rX /opt/android-sdk
USER builder
WORKDIR /work
