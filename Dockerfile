FROM eclipse-temurin:21-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    git \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install OpenJDK 25
RUN wget https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.deb
RUN dpkg -i jdk-25_linux-x64_bin.deb

# Set JAVA_HOME
ENV JAVA_HOME=/usr/lib/jvm/jdk-25-oracle-x64
ENV PATH=$JAVA_HOME/bin:$PATH

# Install SBT
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1001 appuser && \
    mkdir -p /app && \
    chown appuser:appuser /app

WORKDIR /app

USER appuser

COPY --chown=appuser:appuser build.sbt .
COPY --chown=appuser:appuser project/ project/

RUN sbt update

COPY --chown=appuser:appuser src/ src/

EXPOSE 9000

CMD ["sbt", "-J--add-modules=jdk.incubator.vector", "-J--enable-preview", "run"]
