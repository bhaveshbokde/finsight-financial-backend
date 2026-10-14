#!/usr/bin/env bash
#
# FinSight — user-space Redis and Kafka
# -------------------------------------
# Runs Redis and Kafka as your normal user from ~/.local/opt, with no Docker,
# no sudo, no system packages and no changes outside your home directory.
#
# This exists because this machine's Docker socket is root-owned and rootless
# Docker is unavailable (the `uidmap` package is not installed). Both services
# are ordinary user processes; Kafka needs only a JVM, and Redis is built once
# from source with the gcc/make already present.
#
#   ./scripts/local-services.sh install    # download + build (once)
#   ./scripts/local-services.sh start      # start both, wait for health
#   ./scripts/local-services.sh status     # what is running
#   ./scripts/local-services.sh stop       # stop both
#   ./scripts/local-services.sh logs       # tail both logs
#   ./scripts/local-services.sh clean      # stop + delete data (keeps binaries)
#   ./scripts/local-services.sh uninstall  # remove everything under ~/.local/opt
#
# Nothing here requires elevated privileges. To remove it all by hand:
#   rm -rf ~/.local/opt/kafka ~/.local/opt/redis ~/.local/opt/finsight-data
#
set -euo pipefail

KAFKA_VERSION="3.9.0"
KAFKA_SCALA="2.13"
REDIS_VERSION="7.4.1"

OPT="$HOME/.local/opt"
KAFKA_HOME="$OPT/kafka"
REDIS_HOME="$OPT/redis"
DATA="$OPT/finsight-data"
LOGS="$DATA/logs"
DL="$OPT/_dl"

REDIS_PORT=6379
KAFKA_PORT=9092
KAFKA_CONTROLLER_PORT=9093

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

port_open() { timeout 1 bash -c ">/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# ------------------------------------------------------------------ install --

install_all() {
    mkdir -p "$OPT" "$DL" "$DATA" "$LOGS"

    # ---- Kafka: a tarball plus a JVM is all it needs ----
    if [[ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ]]; then
        ok "Kafka already installed at $KAFKA_HOME"
    else
        local tgz="$DL/kafka_${KAFKA_SCALA}-${KAFKA_VERSION}.tgz"
        if [[ ! -s "$tgz" ]]; then
            info "Downloading Kafka ${KAFKA_VERSION}..."
            curl -sSL -o "$tgz" \
              "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/kafka_${KAFKA_SCALA}-${KAFKA_VERSION}.tgz"
        fi
        rm -rf "$KAFKA_HOME"
        mkdir -p "$KAFKA_HOME"
        tar -xzf "$tgz" -C "$KAFKA_HOME" --strip-components=1
        ok "Kafka ${KAFKA_VERSION} installed at $KAFKA_HOME"
    fi

    # ---- Redis: built from source; only redis-server and redis-cli are kept ----
    if [[ -x "$REDIS_HOME/bin/redis-server" ]]; then
        ok "Redis already installed at $REDIS_HOME"
    else
        local tar="$DL/redis-${REDIS_VERSION}.tar.gz"
        if [[ ! -s "$tar" ]]; then
            info "Downloading Redis ${REDIS_VERSION}..."
            curl -sSL -o "$tar" "https://download.redis.io/releases/redis-${REDIS_VERSION}.tar.gz"
        fi
        rm -rf "$DL/redis-src" && mkdir -p "$DL/redis-src"
        tar -xzf "$tar" -C "$DL/redis-src" --strip-components=1

        info "Building Redis (this takes a minute)..."
        # MALLOC=libc avoids needing the bundled jemalloc to build cleanly.
        make -C "$DL/redis-src" -j"$(nproc)" MALLOC=libc >"$LOGS/redis-build.log" 2>&1 \
            || die "Redis build failed. See $LOGS/redis-build.log"

        mkdir -p "$REDIS_HOME/bin"
        cp "$DL/redis-src/src/redis-server" "$DL/redis-src/src/redis-cli" "$REDIS_HOME/bin/"
        ok "Redis ${REDIS_VERSION} built and installed at $REDIS_HOME"
    fi

    write_configs
}

# ------------------------------------------------------------------ configs --

write_configs() {
    mkdir -p "$DATA/redis" "$DATA/kafka" "$LOGS"

    # ---- Redis ----
    # Loopback only, and a cache-appropriate eviction policy: every entry here is
    # reconstructible from PostgreSQL, so evicting the least-recently-used key
    # under memory pressure is correct. The default `noeviction` would start
    # refusing writes instead.
    cat > "$DATA/redis/redis.conf" <<EOF
bind 127.0.0.1
port $REDIS_PORT
dir $DATA/redis
dbfilename finsight.rdb
appendonly yes
appendfilename "finsight.aof"
appenddirname "appendonly"
maxmemory 256mb
maxmemory-policy allkeys-lru
save 900 1
logfile $LOGS/redis.log
daemonize no
protected-mode yes
EOF

    # ---- Kafka (KRaft: broker and controller in one process, no ZooKeeper) ----
    # Only one listener is needed here, unlike the Docker setup: the broker and
    # every client share the host's network namespace, so localhost means the
    # same thing to both. The two-listener dance in docker-compose.yml exists
    # purely because a container's localhost is not the host's.
    cat > "$DATA/kafka/server.properties" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:$KAFKA_CONTROLLER_PORT

listeners=PLAINTEXT://127.0.0.1:$KAFKA_PORT,CONTROLLER://127.0.0.1:$KAFKA_CONTROLLER_PORT
advertised.listeners=PLAINTEXT://127.0.0.1:$KAFKA_PORT
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT

log.dirs=$DATA/kafka/logs

# Single broker: nothing can be replicated, so every internal topic must use 1
# or topic creation fails outright. NOT production values.
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
default.replication.factor=1
min.insync.replicas=1

num.partitions=1
auto.create.topics.enable=true
group.initial.rebalance.delay.ms=0
log.retention.hours=24

# Modest footprint for a development box.
num.network.threads=3
num.io.threads=4
EOF

    ok "Configuration written to $DATA"
}

# -------------------------------------------------------------------- start --

start_redis() {
    if port_open "$REDIS_PORT"; then
        ok "Redis already listening on $REDIS_PORT"
        return
    fi
    [[ -x "$REDIS_HOME/bin/redis-server" ]] || die "Redis not installed. Run: $0 install"

    nohup "$REDIS_HOME/bin/redis-server" "$DATA/redis/redis.conf" \
        >"$LOGS/redis.out" 2>&1 &
    echo $! > "$DATA/redis.pid"

    for _ in $(seq 1 30); do
        if "$REDIS_HOME/bin/redis-cli" -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
            ok "Redis started (pid $(cat "$DATA/redis.pid")), PING -> PONG"
            return
        fi
        sleep 1
    done
    die "Redis did not become healthy. See $LOGS/redis.log"
}

start_kafka() {
    if port_open "$KAFKA_PORT"; then
        ok "Kafka already listening on $KAFKA_PORT"
        return
    fi
    [[ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ]] || die "Kafka not installed. Run: $0 install"

    # KRaft requires the log directory to be formatted with a cluster id once.
    if [[ ! -f "$DATA/kafka/logs/meta.properties" ]]; then
        local cluster_id
        cluster_id="$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)"
        info "Formatting Kafka storage (cluster id $cluster_id)..."
        "$KAFKA_HOME/bin/kafka-storage.sh" format \
            -t "$cluster_id" -c "$DATA/kafka/server.properties" --ignore-formatted \
            >"$LOGS/kafka-format.log" 2>&1 \
            || die "Kafka storage format failed. See $LOGS/kafka-format.log"
        ok "Kafka storage formatted"
    fi

    KAFKA_HEAP_OPTS="-Xms256m -Xmx512m" \
    LOG_DIR="$LOGS" \
    nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$DATA/kafka/server.properties" \
        >"$LOGS/kafka.out" 2>&1 &
    echo $! > "$DATA/kafka.pid"

    info "Waiting for Kafka to accept admin requests..."
    for _ in $(seq 1 60); do
        if "$KAFKA_HOME/bin/kafka-topics.sh" \
             --bootstrap-server 127.0.0.1:"$KAFKA_PORT" --list >/dev/null 2>&1; then
            ok "Kafka started (pid $(cat "$DATA/kafka.pid")), admin API responding"
            return
        fi
        sleep 2
    done
    die "Kafka did not become healthy. See $LOGS/kafka.out"
}

# --------------------------------------------------------------------- stop --

stop_one() {
    local name="$1" pidfile="$2"
    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        kill "$(cat "$pidfile")" 2>/dev/null || true
        for _ in $(seq 1 20); do
            kill -0 "$(cat "$pidfile")" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$(cat "$pidfile")" 2>/dev/null || true
        rm -f "$pidfile"
        ok "$name stopped"
    else
        info "$name not running"
        rm -f "$pidfile"
    fi
}

# ------------------------------------------------------------------- status --

status_all() {
    printf '\n  %-8s %-10s %s\n' "SERVICE" "PORT" "STATE"
    printf '  %-8s %-10s %s\n' "-------" "----" "-----"

    if port_open "$REDIS_PORT"; then
        local pong
        pong="$("$REDIS_HOME/bin/redis-cli" -p "$REDIS_PORT" ping 2>/dev/null || echo "no reply")"
        printf '  %-8s %-10s \033[32m%s\033[0m\n' "Redis" "$REDIS_PORT" "up ($pong)"
    else
        printf '  %-8s %-10s \033[31m%s\033[0m\n' "Redis" "$REDIS_PORT" "down"
    fi

    if port_open "$KAFKA_PORT"; then
        local topics
        topics="$("$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:$KAFKA_PORT \
                  --list 2>/dev/null | wc -l)"
        printf '  %-8s %-10s \033[32m%s\033[0m\n' "Kafka" "$KAFKA_PORT" "up ($topics topics)"
    else
        printf '  %-8s %-10s \033[31m%s\033[0m\n' "Kafka" "$KAFKA_PORT" "down"
    fi

    if port_open 5432; then
        printf '  %-8s %-10s \033[32m%s\033[0m\n' "Postgres" "5432" "up (host service)"
    else
        printf '  %-8s %-10s \033[31m%s\033[0m\n' "Postgres" "5432" "down"
    fi
    echo
}

# --------------------------------------------------------------------- main --

case "${1:-}" in
    install)
        echo; echo "FinSight :: installing user-space services"; echo "------------------------------------------"
        install_all
        echo; info "Next: $0 start"; echo
        ;;
    start)
        echo; echo "FinSight :: starting user-space services"; echo "----------------------------------------"
        write_configs
        start_redis
        start_kafka
        status_all
        ;;
    stop)
        echo; echo "FinSight :: stopping user-space services"; echo "----------------------------------------"
        stop_one "Kafka" "$DATA/kafka.pid"
        stop_one "Redis" "$DATA/redis.pid"
        echo
        ;;
    restart)
        "$0" stop; "$0" start
        ;;
    status)
        status_all
        ;;
    logs)
        tail -f "$LOGS/kafka.out" "$LOGS/redis.log" 2>/dev/null
        ;;
    clean)
        "$0" stop
        rm -rf "$DATA/kafka/logs" "$DATA/redis"/*.rdb "$DATA/redis/appendonly"
        ok "Service data deleted (binaries kept)"
        ;;
    uninstall)
        "$0" stop
        rm -rf "$KAFKA_HOME" "$REDIS_HOME" "$DATA" "$DL"
        ok "Removed everything under $OPT"
        ;;
    *)
        cat <<EOF

FinSight user-space services (no Docker, no sudo)

  $0 install     download Kafka + build Redis into ~/.local/opt
  $0 start       start both and wait until healthy
  $0 stop        stop both
  $0 restart     stop then start
  $0 status      show what is listening
  $0 logs        tail both logs
  $0 clean       stop and delete service data, keep binaries
  $0 uninstall   remove everything under ~/.local/opt

Ports: Redis 127.0.0.1:$REDIS_PORT   Kafka 127.0.0.1:$KAFKA_PORT
Data:  $DATA

EOF
        exit 1
        ;;
esac
