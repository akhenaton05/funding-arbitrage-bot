# Funding Arbitrage Bot

[![Java 22](https://img.shields.io/badge/Java-22-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Production-blue.svg)](https://www.docker.com/)
[![Telegram](https://img.shields.io/badge/Telegram-Notifications-purple.svg)](https://core.telegram.org/bots/api)

An automated delta-neutral arbitrage bot that exploits funding rate discrepancies across decentralized perpetual exchanges(DEX). When a significant spread is detected, the bot simultaneously opens opposing positions on two exchanges — collecting the funding payment while remaining market-neutral.

---

## Supported Exchanges

| Exchange | Type | Auth | Microservice |
|----------|------|------|-------------|
| **Aster** | CEX-style perps | HmacSHA256 | Java (native) |
| **Extended** | StarkNet perps | Ed25519 (StarkNet) | Python/Flask · port `5000` |
| **Lighter** | zkEVM perps | zk-signed transactions | Python/Quart · port `5001` |

Aster is integrated directly into the Java bot via a signed HTTP client. Extended and Lighter each require a dedicated Python microservice because their SDKs depend on chain-specific cryptographic primitives that are impractical to replicate in Java.

---

## Architecture

```
┌─────────────────────────────┐
│   Java Bot  (Spring Boot)   │  ← core logic, scheduler, Telegram
│         systemd             │
└──────────┬──────────────────┘
           │ HTTP
     ┌─────┴──────────────────┐
     │                        │
     ▼                        ▼
Extended Service         Lighter Service
Python / Flask           Python / Quart
Docker · :5000           Docker · :5001
StarkNet Ed25519         zkLighter SDK
     │                        │
     ▼                        ▼
Extended Exchange        Lighter Exchange
```

The Java bot is the single source of truth: it holds all business logic, position state, PnL tracking, and scheduling. The Python services act as thin proxies — they handle SDK initialization, chain signing, and expose a simple REST API for the bot to call.

---

## How It Works

### 1. Signal Detection (every hour at 54min(your time to open))

The bot fetches funding rates from an aggregator API and calculates the spread between each supported exchange pair. Two trading modes are available:

Two modes available:
- **Fast Mode** — spread ≥ configured threshold (default 150 bps). Open before funding, close immediately after payment received (at :01).
- **Smart Mode** — spread ≥ lower threshold (default 50 bps). Hold the position until the spread compresses below a close threshold or a bad-streak counter is hit.

OI rank filtering is also available: positions are only opened for assets with an open-interest rank below a configurable maximum, to minimise slippage on low OI tokens

### 2. Synchronous Opening (parallel, < 3 seconds)

Both legs are opened simultaneously using `CompletableFuture`:

```java
CompletableFuture<String> firstFuture  = CompletableFuture.supplyAsync(() -> exchange1.open(...));
CompletableFuture<String> secondFuture = CompletableFuture.supplyAsync(() -> exchange2.open(...));
CompletableFuture.allOf(firstFuture, secondFuture).get(20, TimeUnit.SECONDS);
```

Position size is delta-neutral: the bot queries the order book of both exchanges, computes the maximum fillable size for the available margin on each side, and uses the minimum of the two.

### 3. Validation

After a 4-second settle window the bot calls `getPositions()` on both exchanges. Both legs must be present with a non-zero size.

### 4. Rollback (Emergency Close)

If validation fails — meaning one leg opened and the other did not — the bot immediately closes the successful leg to eliminate one-sided market exposure:

```
Extended opened ✅  |  Lighter failed ❌  →  close Extended
Lighter opened  ✅  |  Extended failed ❌  →  close Lighter
```

The position ID is rolled back and an error notification is sent via Telegram.

### 5. Closing

- **Fast Mode**: closed automatically at :01 UTC after the funding payment window.
- **Smart Mode**: closed when the spread drops below `closeThreshold` or after `badStreakThreshold` consecutive bad ticks.
- **Manual close**: `/close P-0001` via Telegram, or `/closeall` for all open positions.
- **P&L threshold**: optionally auto-close when net return exceeds a configured percentage.
- **Liquidation guard**: a background job checks all open positions every 10 minutes and closes the hedge if one leg has been liquidated or closed externally.

---

## Key Features

- **50+ trading pairs** monitored in a single scan
- **Delta-neutral sizing** — matching position sizes via live order-book data from both exchanges
- **Parallel open/close** — both legs executed concurrently, average time < 3 seconds
- **Automatic rollback** on partial failures
- **Bi-directional position validation** after every open
- **Funding PnL tracking** — funding payments accumulated per leg, included in net PnL
- **Unrealized PnL** — calculated against live bid/ask, not mark price, for realistic estimates
- **Stop Loss / Take Profit** — supported on Aster (STOP_MARKET / TAKE_PROFIT_MARKET orders)
- **OI rank filter** — skip low-liquidity assets
- **Telegram bot** — real-time alerts, position status, manual controls

---

## Extended Service (Python · port 5000)

Handles all communication with the Extended (StarkNet) exchange. The `x10-python-trading-starknet` SDK requires Ed25519 signatures generated using StarkNet primitives — impractical to port to Java.

**Endpoints used by the bot:**

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/order/market` | Open market order (async, returns `external_id`) |
| `POST` | `/positions/close` | Close position (async) |
| `GET` | `/order/status/<id>` | Poll order status |
| `GET` | `/positions` | Get open positions |
| `GET` | `/balance` | Account balance |
| `PATCH` | `/user/leverage` | Set leverage |
| `GET` | `/funding/history` | Funding payment history |
| `GET` | `/api/v1/info/markets/<m>/orderbook` | Order book |

Orders are placed asynchronously: the endpoint returns `202 Accepted` with an `external_id`, and the bot polls `/order/status` to confirm execution. Market data (prices, precision) is cached with a configurable TTL (default 30 seconds).

---

## Lighter Service (Python · port 5001)

Handles all communication with the Lighter (zkEVM) exchange. Uses `lighter-sdk` for zk-signed transaction construction.

**Endpoints used by the bot:**

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/order/market` | Open market order (IOC) |
| `POST` | `/positions/close` | Close position (reduce-only IOC) |
| `GET` | `/positions` | Get open positions |
| `GET` | `/balance` | Account balance |
| `POST` | `/user/leverage` | Set leverage (on-chain tx) |
| `GET` | `/market/<m>/orderbook` | Order book |
| `GET` | `/market/<m>/funding-rate` | Current funding rate |
| `GET` | `/funding/payments` | Accumulated funding payments |

Markets are loaded dynamically at startup from the Lighter order books API. Leverage is cached — on startup the service reads open positions and infers the current leverage from `initial_margin_fraction`.

---

## Deployment

```
Ubuntu Server
├── systemd
│   └── Java Bot (port 8080)
├── Docker
│   ├── extended-service (port 5000)  ← restart: unless-stopped
│   └── lighter-service  (port 5001)  ← restart: unless-stopped
└── Telegram Bot
```

The Java bot is managed by systemd with `Restart=on-failure`. Both Python services run in Docker with `restart: unless-stopped`. A proxy can be configured for all three services independently.

### Configuration

Create `src/main/resources/application.yaml` (see `application.yaml.example`) and `.env` files in each service directory before starting.

```bash
# Start Python services
cd extended-service && docker compose up -d
cd lighter-service  && docker compose up -d

# Build and start Java bot
mvn clean package -DskipTests
java -jar target/CryptoTgBot-1.0-SNAPSHOT.jar
```

---

## Telegram Commands

| Command | Description |
|---------|-------------|
| `/track` | Subscribe to alerts |
| `/untrack` | Unsubscribe |
| `/rates` | Show top 10 current arbitrage spreads |
| `/trades` | Show position history and PnL |
| `/close P-XXXX` | Manually close a position by ID |
| `/closeall` | Close all open positions |
| `/pospnl P-XXXX` | Show current PnL for a position |

---

## ⚠️ Disclaimer

Trading perpetual futures involves significant financial risk, including the possibility of total loss of capital. This software is provided for educational and research purposes. Always test on paper accounts before deploying real funds. The author accepts no responsibility for financial losses.
