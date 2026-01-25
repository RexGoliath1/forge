# Forge Daemon Docker Image
# Runs the MTG Forge game engine in headless daemon mode for RL training

FROM eclipse-temurin:21-jre-alpine

# Set working directory
WORKDIR /forge

# Install netcat for health checks
RUN apk add --no-cache netcat-openbsd

# Copy the JAR file
COPY forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar forge-daemon.jar

# Copy resource files (cards, editions, etc.)
COPY forge-gui/res ./res

# Copy test decks (optional - may not exist)
COPY forge-gui/res/decks/constructed/ ./res/decks/constructed/

# Environment variables
ENV JAVA_OPTS="-Xmx2g -Djava.awt.headless=true"
ENV FORGE_PORT=17171

# Expose the daemon port
EXPOSE ${FORGE_PORT}

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD echo "STATUS" | nc -w 5 localhost ${FORGE_PORT} | grep -q "FORGE DAEMON STATUS" || exit 1

# Run the daemon
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar forge-daemon.jar daemon"]
