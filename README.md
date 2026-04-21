# Funding Arbitrage Bot

[![Java 22](https://img.shields.io/badge/Java-22-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Production-blue.svg)](https://www.docker.com/)
[![Telegram](https://img.shields.io/badge/Telegram-Notifications-purple.svg)](https://core.telegram.org/bots/api)

An automated delta-neutral arbitrage bot that exploits funding rate discrepancies across decentralized perpetual exchanges. When a significant spread is detected, the bot simultaneously opens opposing positions on two exchanges — collecting the funding payment while remaining market-neutral.

---

## Supported Exchanges

| Exchange | Type | Auth | Integration |
|----------|------|------|-------------|
| **Aster** | CEX-style perps | HmacSHA256 | Java (native) |
| **Extended** | StarkNet perps | Ed25519 (StarkNet) | Python/Flask · port `5000` |
| **Lighter** | zkEVM perps | zk-signed transactions | Python/Quart · port `5001` |
| **Hyperliquid** | CEX-style perps | Ed25519 | Python/Flask · port `5002` |


Aster integrated directly into the Java bot via signed HTTP clients. Hyperliquid, Extended and Lighter each require a dedicated Python microservice because their SDKs depend on chain-specific cryptographic primitives that are impractical to replicate in Java.

---

## Architecture
```
            ┌──────────────────────────────────┐
            │    Java Bot  (Spring Boot)       │  ← core logic, scheduler, Telegram
            │          systemd                 │
            └──────────────────┬───────────────┘
                               │ HTTP
      ┌────────────────────────┌──────────────────────────┐
      │                        │                          │       
      ▼                        ▼                          ▼
Hyperliquid Service      Extended Service           Lighter Service
Python / Flask           Python / Flask             Python / Quart
Docker :5002             Docker :5000               Docker :5001
Ed25519                  StarkNet Ed25519           zkLighter SDK
   │                           │                         │
   ▼                           ▼                         ▼
Hyperliquid                 Extended                      Lighter
```

The Java bot holds all core logic: signal detection, position state, PnL tracking, risk management, and scheduling. The Python services act as thin proxies — they handle SDK initialization, chain signing, and expose a simple REST API for the bot to call.

---

## How It Works

### 1. Signal Detection (every hour at :54)

The bot fetches funding rates from an aggregator API and calculates the spread for every pair of supported exchanges. Rates are cached for 60 seconds to reduce API load. Two trading modes are available:

- **Fast Mode** — spread ≥ configured threshold (default 150 bps). Opens before funding, closes automatically at :01 after the payment is received.
- **Smart Mode** — spread ≥ lower threshold (default 50 bps). Holds the position until the spread compresses below a close threshold, or a funding rate flip is detected.

Optional filters:
- **OI rank filter** — skip assets with an open-interest rank above a configurable maximum to avoid slippage on low-liquidity tokens.
- **Ticker blacklist** — a dynamic per-ticker blocklist configurable via Telegram or `application.yml`, persisted across restarts via config.

### 2. Delta-Neutral Opening (parallel, < 3 seconds)

Both legs are opened simultaneously using `CompletableFuture`:

```java
CompletableFuture<String> firstFuture  = CompletableFuture.supplyAsync(() -> exchange1.open(...));
CompletableFuture<String> secondFuture = CompletableFuture.supplyAsync(() -> exchange2.open(...));
CompletableFuture.allOf(firstFuture, secondFuture).get(20, TimeUnit.SECONDS);
```

Position size is delta-neutral: the bot queries the live order book of both exchanges, calculates the maximum fillable size for the available margin on each side, and uses the minimum of the two — rounded to 2 decimal places.

Pre-open validations:
- Both exchanges must have funding payment within 60 minutes
- Available margin must exceed N amount(10$ by default)
- Max leverage capped at the minimum supported by both exchanges
- Duplicate position guard — same ticker + exchange pair cannot be opened twice

### 3. Post-Open Validation

After a 5-second settle window the bot calls `getPositions()` on both exchanges. Both legs must be present with a non-zero size. Delta-neutrality is verified and logged with size delta % and notional difference %.

### 4. Rollback on Partial Failure

If validation fails — one leg opened and the other did not — the bot immediately closes the successful leg to eliminate one-sided market exposure:

Exchange A opened ✅ | Exchange B failed ❌ → close Exchange A immediately

Exchange A failed ❌ | Exchange B opened ✅ → close Exchange B immediately


The position ID is rolled back and an error notification is sent via Telegram.

### 5. Closing

- **Fast Mode**: auto-closed at :01 UTC after funding payment.
- **Smart Mode**: closed when funding rate flips direction (bot detects the spread has reversed).
- **Manual close**: `/close P-0001` via Telegram, or `/closeall`.
- **P&L threshold**: auto-close when net return exceeds a configured percentage (with a 10-minute grace period after open).
- **Liquidation guard**: background job every 10 minutes checks all positions; if one leg is liquidated or closed externally, the hedge is closed immediately after 3 consecutive empty-position confirmations.

---

## PnL Tracking

Unrealized PnL is calculated against live order book bid/ask prices, not mark price, for realistic exit estimates:
- **LONG leg** closing → uses best **bid** price
- **SHORT leg** closing → uses best **ask** price
- Fallback to mark price ± 0.4% slippage if order book is unavailable

Each position tracks:
- Gross PnL (price movement only)
- Funding payments accumulated per leg
- Open and close fees
- Net PnL = Gross + Funding − Fees
- Exit readiness signal: `CLOSE NOW / CAN CLOSE / SLIP RISK / RATE DROP / HOLD / NEUTRAL`

Funding prediction runs at :59 (1 minute before payment) and accumulates per-exchange funding via each exchange's native funding history API.

---

## Risk Management

- **Liquidation price proximity alerts**: warning at configurable % distance, critical alert closer to liquidation — checked every 10 minutes
- **SL/TP orders**: supported on Aster via `STOP_MARKET` / `TAKE_PROFIT_MARKET` orders, placed immediately after open if enabled
- **Funding rate flip detection**: Smart Mode positions are monitored for direction reversal; Telegram notification is sent if the spread flips
- **Closure verification**: after every close, both positions are confirmed empty; if not, a retry close is attempted automatically

---

### Key Config (`application.yml`)

```yaml
funding:
  thresholds:
    smart-mode-rate: 50    # bps, minimum spread for Smart Mode
    fast-mode-rate: 150    # bps, minimum spread for Fast Mode
  oi:
    enabled: true
    max-rank: 50           # skip tokens with OI rank > 50
  ticker-blacklist:
    - MSTR
    - CRCL
  sltp:
    enabled: true
    stop-loss-percent: 5.0
    take-profit-percent: 10.0
  pnl:
    enable-notifications: true
    threshold-percent: 2.0
  liquidation:
    warn: 60               # % progress toward liquidation price
    critical: 85
```

---

## Telegram Commands

| Command | Description |
|---------|-------------|
| `/track` | Subscribe to alerts |
| `/untrack` | Unsubscribe |
| `/rates` | Top 10 current arbitrage spreads |
| `/trades` | Position history and PnL summary |
| `/history` | Detailed trade statistics (day / week / month / all) |
| `/balance` | Current balance on all exchanges |
| `/pnl P-XXXX` | Live PnL for an open position |
| `/close P-XXXX` | Manually close a position |
| `/closeall` | Close all open positions |
| `/blacklist` | Show current ticker blacklist |
| `/blacklist_add TICKER` | Add ticker to blacklist |
| `/blacklist_remove TICKER` | Remove ticker from blacklist |

---

## ⚠️ Disclaimer

Trading perpetual futures involves significant financial risk, including the possibility of total loss of capital. This software is provided for educational and research purposes only. Always test with minimal funds before deploying. The author accepts no responsibility for financial losses.
