# Start with Ubuntu 24.04 LTS
FROM ubuntu:24.04

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Update package list and install basic dependencies
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    git \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install OpenJDK 24
RUN wget https://download.oracle.com/java/24/latest/jdk-24_linux-x64_bin.deb
RUN dpkg -i jdk-24_linux-x64_bin.deb

# Set JAVA_HOME
ENV JAVA_HOME=/usr/lib/jvm/jdk-24-oracle-x64
ENV PATH=$JAVA_HOME/bin:$PATH

# Install SBT
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*


# Create a non-root user for security
RUN useradd -m -u 1001 appuser && \
    mkdir -p /app && \
    chown appuser:appuser /app

# Set working directory
WORKDIR /app

# Switch to non-root user
USER appuser

# Copy build files first (for better caching)
COPY --chown=appuser:appuser build.sbt .
COPY --chown=appuser:appuser project/ project/

# Download dependencies (this will be cached if build.sbt doesn't change)
RUN sbt update

# Copy source code
COPY --chown=appuser:appuser src/ src/

# Expose the port the Akka HTTP server will use
EXPOSE 9000

# Run the application with Vector API enabled
CMD ["sbt", "-J--add-modules=jdk.incubator.vector", "-J--enable-preview", "run"]