"""
Lighter Exchange Service - FINAL FIXED VERSION
Динамическая загрузка всех рынков через API

ИСПРАВЛЕНО:
- orderbook: order_book_orders(market_id=..., limit=...) - ПРАВИЛЬНЫЙ метод
- все отступы исправлены
- leverage response format
- leverage cache: init при старте + запись после успешного set
- get_max_leverage: исправлен шаг 2 (account_positions → account)
"""

from quart import Quart, request, jsonify
from quart_cors import cors
import asyncio
import aiohttp
import lighter
import os
import logging
from datetime import datetime
from decimal import Decimal
from dotenv import load_dotenv

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

app = Quart(__name__)
app = cors(app, allow_origin="*")

# ============================================
# CONFIGURATION
# ============================================

BASE_URL = os.getenv('LIGHTER_BASE_URL', 'https://testnet.zklighter.elliot.ai')
API_KEY_INDEX = int(os.getenv('LIGHTER_API_KEY_INDEX', '0'))
API_PRIVATE_KEY = os.getenv('LIGHTER_API_PRIVATE_KEY')
ACCOUNT_INDEX = int(os.getenv('LIGHTER_ACCOUNT_INDEX', '0'))
L1_ADDRESS = os.getenv('LIGHTER_L1_ADDRESS')

# Global clients
api_client = None
signer_client = None

# Кэш leverage — заполняется при первом запросе
MAX_LEVERAGE_CACHE: dict[str, int] = {}

# Хардкод максимального плеча по тикеру
KNOWN_MAX_LEVERAGE: dict[str, int] = {
    "BTC": 20, "ETH": 20, "WETH": 20, "WBTC": 20, "SOL": 20,
    "AVAX": 10, "MATIC": 10, "ARB": 10, "OP": 10,
    "ATOM": 10, "NEAR": 10, "APT": 10, "SUI": 10,
    "JUP": 10, "UNI": 10, "ETHFI": 10, "ADA": 10,
    "LINK": 10, "AAVE": 10, "MKR": 10, "CRV": 10,
    "DOT": 10, "ALGO": 10, "FTM": 10, "ICP": 10,
    "NMR": 8, "COIN": 8, "LDO": 8,
    "MEGA": 5, "PEPE": 5, "BONK": 5, "SHIB": 5,
    "PIPPIN": 3, "DEGEN": 3, "FLOKI": 3, "RIVER": 3,
    "ARC": 3, "ZORA": 3, "STABLE": 3, "MYX": 3,
    "VVV": 3, "USELESS": 3, "AERO": 3,
    "USDCHF": 10, "EURUSD": 10, "GBPUSD": 10,
    "USDJPY": 10, "GBPJPY": 10, "AUDUSD": 10,
    "XAU": 10, "XAG": 10,
}


# Кэши
market_metadata_cache = {}
MAX_LEVERAGE_CACHE = {}
leverage_settings = {}
SYSTEM_MAX_LEVERAGE = 20

# Emergency fallback
EMERGENCY_FALLBACK_MARKETS = {
    "WETH-USDC": {"market_id": 0, "size_decimals": 4, "price_decimals": 2},
    "WBTC-USDC": {"market_id": 1, "size_decimals": 5, "price_decimals": 1},
    "SOL-USDC":  {"market_id": 2, "size_decimals": 3, "price_decimals": 3},
}

# ============================================
# MARKET LOADING
# ============================================

async def load_markets_dynamic():
    """Загружаем только PERP рынки + создаём алиасы"""
    loaded_count = 0

    try:
        logger.info("📊 Loading markets via OrderApi.order_books()")
        order_api = lighter.OrderApi(api_client)

        response = await order_api.order_books()

        if hasattr(response, 'order_books'):
            order_books = response.order_books
            logger.info(f"   Found {len(order_books)} orderbooks")

            perp_count = 0
            spot_count = 0

            for ob in order_books:
                status = getattr(ob, 'status', 'active')
                if status != 'active':
                    continue

                symbol = ob.symbol
                market_id = ob.market_id
                market_type = getattr(ob, 'market_type', 'perp')

                size_decimals = ob.supported_size_decimals
                price_decimals = ob.supported_price_decimals

                if symbol and market_id is not None:
                    clean_symbol = symbol.replace('/USDC', '').replace('/USD', '').upper()

                    metadata = {
                        'market_id': market_id,
                        'size_decimals': size_decimals,
                        'price_decimals': price_decimals,
                        'symbol': clean_symbol,
                        'market_type': market_type,
                        'original_symbol': symbol,
                    }

                    if market_type == 'perp':
                        market_metadata_cache[clean_symbol] = metadata
                        perp_count += 1

                        if clean_symbol == 'ETH':
                            market_metadata_cache['WETH'] = metadata
                            market_metadata_cache['ETH-USD'] = metadata
                            market_metadata_cache['WETH-USDC'] = metadata
                        elif clean_symbol == 'BTC':
                            market_metadata_cache['WBTC'] = metadata
                            market_metadata_cache['BTC-USD'] = metadata
                            market_metadata_cache['WBTC-USDC'] = metadata
                        elif clean_symbol in ['SOL', 'ARB', 'OP']:
                            market_metadata_cache[f"{clean_symbol}-USD"] = metadata
                            market_metadata_cache[f"{clean_symbol}-USDC"] = metadata
                    else:
                        spot_count += 1
                        market_metadata_cache[f"{clean_symbol}-SPOT"] = metadata

            loaded_count = perp_count
            logger.info(f"✅ Loaded {perp_count} PERP markets, {spot_count} SPOT markets")
            return loaded_count

    except Exception as e:
        logger.error(f"Failed to load markets: {e}", exc_info=True)

    if loaded_count < 3:
        logger.warning("⚠️ Loading emergency fallback")
        market_metadata_cache.update(EMERGENCY_FALLBACK_MARKETS)
        loaded_count = len(market_metadata_cache)

    return loaded_count


# ============================================
# INITIALIZATION
# ============================================

async def detect_account_by_l1():
    """Определяем account_index по L1 адресу"""
    if not L1_ADDRESS:
        return None

    logger.info(f"🔍 Detecting account by L1: {L1_ADDRESS[:10]}...")

    try:
        account_api = lighter.AccountApi(api_client)
        resp = await account_api.accounts_by_l1_address(l1_address=L1_ADDRESS)

        if resp:
            indices = []
            if hasattr(resp, 'master_account') and resp.master_account:
                indices.append(resp.master_account.index)
            if hasattr(resp, 'sub_accounts') and resp.sub_accounts:
                indices += [sub.index for sub in resp.sub_accounts]

            if indices:
                logger.info(f"✅ Detected account indices: {indices}")
                return indices[0]
    except Exception as e:
        logger.error(f"L1 detection failed: {e}")

    return None


async def init_clients():
    """Инициализация API и Signer клиентов"""
    global api_client, signer_client, ACCOUNT_INDEX

    logger.info("Initializing Lighter clients...")

    try:
        configuration = lighter.Configuration(host=BASE_URL)
        api_client = lighter.ApiClient(configuration)
        logger.info(f"✅ API client: {BASE_URL}")
    except Exception as e:
        logger.error(f"❌ ApiClient failed: {e}", exc_info=True)
        raise

    detected = await detect_account_by_l1()
    if detected is not None:
        ACCOUNT_INDEX = detected
        logger.info(f"✅ Account index: {ACCOUNT_INDEX} (auto-detected)")
    else:
        logger.info(f"ℹ️ Using env account index: {ACCOUNT_INDEX}")

    if not API_PRIVATE_KEY:
        logger.warning("⚠️ No API_PRIVATE_KEY - READ-ONLY mode")
        signer_client = None
        return

    try:
        api_private_keys = {API_KEY_INDEX: API_PRIVATE_KEY}
        logger.info(f"Creating SignerClient: account={ACCOUNT_INDEX}, key_index={API_KEY_INDEX}")

        signer_client = lighter.SignerClient(
            url=BASE_URL,
            account_index=ACCOUNT_INDEX,
            api_private_keys=api_private_keys
        )

        err = signer_client.check_client()
        if err is not None:
            error_msg = err.decode('utf-8') if isinstance(err, bytes) else str(err)
            raise Exception(f"Check failed: {error_msg}")

        logger.info("✅ Signer client initialized - TRADING ENABLED ✅")

    except Exception as e:
        logger.error(f"❌ Signer failed: {e}", exc_info=True)
        signer_client = None
        logger.warning("⚠️ Continuing in READ-ONLY mode")


# ✅ НОВОЕ: инициализация кэша leverage из открытых позиций
async def init_leverage_cache():
    """Загружаем реальные leverage из открытых позиций при старте"""
    try:
        account_api = lighter.AccountApi(api_client)
        raw = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

        if not hasattr(raw, 'accounts') or not raw.accounts:
            logger.info("   No accounts found for leverage cache init")
            return

        positions = raw.accounts[0].positions or []

        if not positions:
            logger.info("   No open positions for leverage cache init")
            return

        for pos in positions:
            symbol = (getattr(pos, 'symbol', '') or '').upper()
            clean_symbol = symbol.replace('-USDC', '').replace('/USDC', '')
            imf = float(getattr(pos, 'initial_margin_fraction', 0) or 0)

            if clean_symbol and imf > 0:
                real_leverage = int(round(100 / imf))
                MAX_LEVERAGE_CACHE[clean_symbol] = real_leverage
                leverage_settings[clean_symbol] = real_leverage
                logger.info(f"   📦 Init cache: {clean_symbol} = {real_leverage}x (IMF={imf})")

        logger.info(f"✅ Leverage cache initialized: {MAX_LEVERAGE_CACHE}")

    except Exception as e:
        logger.warning(f"Failed to init leverage cache: {e}")


@app.before_serving
async def startup():
    """Startup: инициализация клиентов + загрузка рынков"""

    await init_clients()

    if api_client:
        logger.info("=" * 60)
        logger.info("🔄 Loading markets dynamically...")
        logger.info("=" * 60)

        loaded = await load_markets_dynamic()

        logger.info("=" * 60)
        logger.info(f"✅ {loaded} markets loaded successfully")
        logger.info("=" * 60)

        sample = list(market_metadata_cache.keys())[:10]
        logger.info(f"Sample markets: {', '.join(sample)}")

        # ✅ НОВОЕ: инициализируем кэш leverage из открытых позиций
        await init_leverage_cache()

    logger.info("✅ App ready to serve requests")


# ============================================
# HELPER FUNCTIONS
# ============================================

async def refresh_signer_nonce():
    """Обновить nonce в signer client"""
    global signer_client

    if not signer_client:
        return

    try:
        logger.info("🔄 Refreshing signer nonce...")

        account_api = lighter.AccountApi(api_client)
        account_response = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

        if hasattr(account_response, 'accounts') and account_response.accounts:
            acc = account_response.accounts[0]
            current_nonce = getattr(acc, 'nonce', None)

            if current_nonce is not None:
                logger.info(f"   Current nonce from API: {current_nonce}")

                if hasattr(signer_client, 'nonce_manager'):
                    signer_client.nonce_manager.hard_refresh_nonce(API_KEY_INDEX)
                    logger.info("   ✅ Nonce manager refreshed")

    except Exception as e:
        logger.error(f"Failed to refresh nonce: {e}")


async def get_market_metadata(symbol: str) -> dict:
    """Получаем metadata рынка с поддержкой вариаций"""
    symbol_upper = symbol.upper()

    if symbol_upper in market_metadata_cache:
        return market_metadata_cache[symbol_upper]

    variants = [
        symbol_upper,
        symbol_upper.replace("-", ""),
        symbol_upper.replace("-USD", "-USDC"),
        symbol_upper + "-USDC",
        "W" + symbol_upper if not symbol_upper.startswith("W") else symbol_upper,
    ]

    for variant in variants:
        if variant in market_metadata_cache:
            logger.debug(f"Market variant match: {symbol} → {variant}")
            return market_metadata_cache[variant]

    if len(market_metadata_cache) < 20:
        logger.info(f"Market {symbol} not found, reloading...")
        await load_markets_dynamic()

        for variant in variants:
            if variant in market_metadata_cache:
                return market_metadata_cache[variant]

    available = list(market_metadata_cache.keys())[:20]
    raise ValueError(f"Market '{symbol}' not found. Available: {available}")


async def get_mark_price(market_id: int, symbol: str) -> float:
    """Получаем mark price для рынка"""
    try:
        order_api = lighter.OrderApi(api_client)

        try:
            details = await asyncio.wait_for(
                order_api.order_book_details(market_id),
                timeout=5.0
            )

            if details:
                if isinstance(details, dict):
                    data = details.get('data', details)
                    mark_price = float(data.get('mark_price', 0))
                elif hasattr(details, 'mark_price'):
                    mark_price = float(details.mark_price)
                elif hasattr(details, 'data') and hasattr(details.data, 'mark_price'):
                    mark_price = float(details.data.mark_price)
                else:
                    mark_price = 0

                if mark_price > 0:
                    logger.info(f"   ✅ mark_price=${mark_price:.2f} from API")
                    return mark_price

        except (asyncio.TimeoutError, Exception) as e:
            logger.debug(f"orderBookDetails failed: {e}")

        try:
            orderbook = await order_api.order_book_orders(market_id=market_id, limit=1)

            if orderbook and hasattr(orderbook, 'bids') and hasattr(orderbook, 'asks'):
                bids = orderbook.bids
                asks = orderbook.asks

                if bids and asks and len(bids) > 0 and len(asks) > 0:
                    best_bid = float(bids[0][0]) if isinstance(bids[0], list) else float(bids[0].price)
                    best_ask = float(asks[0][0]) if isinstance(asks[0], list) else float(asks[0].price)

                    mid_price = (best_bid + best_ask) / 2
                    logger.info(f"   ✅ mid_price=${mid_price:.2f} from orderbook")
                    return mid_price
        except Exception as e:
            logger.debug(f"orderbook midpoint failed: {e}")

        price_map = {
            'ETH': 2700.0, 'WETH': 2700.0,
            'BTC': 98000.0, 'WBTC': 98000.0,
            'SOL': 135.0, 'XRP': 2.5, 'BNB': 620.0,
            'ARB': 0.65, 'OP': 1.85, 'LINK': 18.5,
            'DOGE': 0.18, 'XAU': 2850.0, 'XAG': 32.5,
            'EURUSD': 1.08, 'GBPUSD': 1.27, 'USDJPY': 149.5,
        }

        fallback_price = price_map.get(symbol.upper(), 100.0)
        logger.warning(f"   ⚠️ Using fallback price ${fallback_price} for {symbol}")
        return fallback_price

    except Exception as e:
        logger.error(f"Failed to get mark price: {e}")
        return 100.0


async def get_real_market_price(market_id: int, symbol: str, side: str = 'BUY') -> float:
    """Получаем РЕАЛЬНУЮ рыночную цену из orderbook"""
    try:
        order_api = lighter.OrderApi(api_client)

        orderbook = await asyncio.wait_for(
            order_api.order_book_orders(market_id=market_id, limit=10),
            timeout=5.0
        )

        if orderbook and hasattr(orderbook, 'bids') and hasattr(orderbook, 'asks'):
            bids = orderbook.bids
            asks = orderbook.asks

            logger.info(f"   📊 Orderbook: bids={len(bids)}, asks={len(asks)}")

            if bids and asks and len(bids) > 0 and len(asks) > 0:
                if isinstance(bids[0], list):
                    best_bid = float(bids[0][0])
                    best_ask = float(asks[0][0])
                elif isinstance(bids[0], tuple):
                    best_bid = float(bids[0][0])
                    best_ask = float(asks[0][0])
                else:
                    best_bid = float(getattr(bids[0], 'price', 0))
                    best_ask = float(getattr(asks[0], 'price', 0))

                if best_bid > 0 and best_ask > 0:
                    price = best_bid if side == 'SELL' else best_ask
                    logger.info(f"   ✅ Real {side} price: ${price:.2f} (bid={best_bid:.2f}, ask={best_ask:.2f})")
                    return price

        logger.warning(f"   ⚠️ Could not get orderbook for {symbol}")
        return None

    except Exception as e:
        logger.error(f"Failed to get orderbook: {e}")
        return None


# ============================================
# ENDPOINTS - BASIC
# ============================================

@app.route('/', methods=['GET'])
async def health_check():
    """Health check"""
    return jsonify({
        "status": "OK",
        "service": "lighter-service-fixed",
        "timestamp": datetime.utcnow().isoformat(),
        "trading_enabled": signer_client is not None,
        "account_index": ACCOUNT_INDEX,
        "markets_loaded": len(market_metadata_cache),
        "leverage_cache": MAX_LEVERAGE_CACHE,  # ✅ показываем кэш в health check
        "base_url": BASE_URL
    })


@app.route('/markets', methods=['GET'])
async def get_markets():
    """Возвращаем список всех загруженных рынков"""
    unique_markets = {}
    for symbol, meta in market_metadata_cache.items():
        market_id = meta['market_id']
        if market_id not in unique_markets:
            unique_markets[market_id] = {
                "symbol": meta["symbol"],
                "market_id": market_id,
                "size_decimals": meta["size_decimals"],
                "price_decimals": meta["price_decimals"],
                "min_size": meta.get("min_size"),
                "tick_size": meta.get("tick_size"),
            }

    markets_list = sorted(unique_markets.values(), key=lambda x: x['market_id'])

    return jsonify({
        "status": "OK",
        "count": len(markets_list),
        "data": markets_list
    })


@app.route('/markets/reload', methods=['POST'])
async def reload_markets():
    """Endpoint для перезагрузки рынков"""
    try:
        logger.info("🔄 Manual market reload requested")
        market_metadata_cache.clear()
        loaded = await load_markets_dynamic()

        return jsonify({
            "status": "OK",
            "message": f"Reloaded {loaded} markets",
            "count": len(market_metadata_cache)
        })
    except Exception as e:
        logger.error(f"Reload failed: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


@app.route('/balance', methods=['GET'])
async def get_balance():
    """Получить баланс аккаунта"""
    try:
        account_api = lighter.AccountApi(api_client)
        raw = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

        if not hasattr(raw, 'accounts') or not raw.accounts:
            return jsonify({
                "status": "OK",
                "data": {
                    "total": "0",
                    "available_for_trade": "0",
                    "margin_used": "0",
                    "equity": "0"
                }
            })

        acc = raw.accounts[0]
        available = float(acc.available_balance or 0)
        total_asset = float(acc.total_asset_value or acc.collateral or 0)
        margin_used = max(0, total_asset - available)

        return jsonify({
            "status": "OK",
            "data": {
                "total": f"{total_asset:.6f}",
                "available_for_trade": f"{available:.6f}",
                "margin_used": f"{margin_used:.6f}",
                "equity": f"{total_asset:.6f}"
            }
        })
    except Exception as e:
        logger.error(f"Balance error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: POSITIONS
# ============================================

@app.route('/positions', methods=['GET'])
async def get_positions():
    """Получить открытые позиции"""
    try:
        market = request.args.get('market')
        side = request.args.get('side')

        account_api = lighter.AccountApi(api_client)
        raw = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

        if not hasattr(raw, 'accounts') or not raw.accounts:
            return jsonify({"status": "OK", "data": []}), 200

        acc = raw.accounts[0]
        positions = acc.positions or []

        if not positions:
            logger.info("⚠️ No positions in account")
            return jsonify({"status": "OK", "data": []}), 200

        logger.info(f"📊 Found {len(positions)} positions")

        formatted = []
        for pos in positions:
            symbol = getattr(pos, 'symbol', None)
            sign = getattr(pos, 'sign', 0)
            position_size = float(getattr(pos, 'position', 0) or 0)

            if sign > 0:
                position_side = 'LONG'
            elif sign < 0:
                position_side = 'SHORT'
                position_size = abs(position_size)
            else:
                position_side = 'UNKNOWN'

            logger.debug(f"   Raw: {symbol} {position_side} size={position_size} sign={sign}")

            if market and symbol:
                symbol_match = (
                    symbol.upper() == market.upper() or
                    symbol.upper().replace('-USDC', '') == market.upper() or
                    symbol.upper().replace('/USDC', '') == market.upper()
                )
                if not symbol_match:
                    logger.debug(f"   Filtered by market: {symbol} != {market}")
                    continue

            if side and position_side:
                if position_side.upper() != side.upper():
                    logger.debug(f"   Filtered by side: {position_side} != {side}")
                    continue

            if position_size == 0:
                if not market and not side:
                    logger.debug(f"   Filtered zero size (no filters): {symbol}")
                    continue
                else:
                    logger.info(f"   ⚠️ Filtered position with size=0: {symbol} {position_side}")

            formatted.append({
                "market": symbol,
                "side": position_side,
                "size": str(position_size),
                "open_price": str(getattr(pos, 'avg_entry_price', 0) or 0),
                "mark_price": None,
                "position_value": str(getattr(pos, 'position_value', 0) or 0),
                "unrealised_pnl": str(getattr(pos, 'unrealized_pnl', 0) or 0),
                "realized_pnl": str(getattr(pos, 'realized_pnl', 0) or 0),
                "margin": str(getattr(pos, 'allocated_margin', 0) or 0),
                "liquidation_price": str(getattr(pos, 'liquidation_price', 0) or 0),
                "leverage": f"{100 / float(getattr(pos, 'initial_margin_fraction', 5) or 5):.0f}x",
                "funding_paid": str(getattr(pos, 'total_funding_paid_out', 0) or 0),
            })

            logger.info(f"   ✅ Including: {symbol} {position_side} {position_size}")

        logger.info(f"✅ Returning {len(formatted)} positions")
        return jsonify({"status": "OK", "data": formatted}), 200

    except Exception as e:
        logger.error(f"Positions error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: FUNDING PAYMENTS
# ============================================

@app.route('/market/<string:symbol>/funding-rate', methods=['GET'])
async def get_funding_rate(symbol: str):
    try:
        metadata = await get_market_metadata(symbol)
        market_id = metadata['market_id']

        candlestick_api = lighter.CandlestickApi(api_client)

        now = int(datetime.utcnow().timestamp())

        response = await asyncio.wait_for(
            candlestick_api.fundings(
                market_id=market_id,
                resolution="1h",
                start_timestamp=now - 7200,
                end_timestamp=now,
                count_back=1
            ),
            timeout=5.0
        )

        fundings = getattr(response, 'fundings', [])
        if not fundings:
            return jsonify({"status": "OK", "market": symbol, "funding_rate": 0.0}), 200

        latest = fundings[-1]

        rate_raw = float(getattr(latest, 'rate', 0) or 0)
        rate = rate_raw / 100  # 0.0688% → 0.000688

        direction = getattr(latest, 'direction', None)  # 'long' платит или 'short' платит

        # direction='short' означает SHORT платит LONG → rate положительный для LONG
        # direction='long' означает LONG платит SHORT → rate отрицательный для LONG
        signed_rate = rate if direction == 'short' else -rate

        logger.info(f"[Funding] {symbol} rate={rate} direction={direction} signed={signed_rate}")

        return jsonify({
            "status": "OK",
            "market": symbol,
            "market_id": market_id,
            "funding_rate": signed_rate,
            "funding_rate_raw": rate,
            "direction": direction,
            "funding_rate_8h": signed_rate * 8
        }), 200

    except Exception as e:
        logger.error(f"Funding rate error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route('/funding/payments', methods=['GET'])
async def get_funding_payments():
    try:
        market = request.args.get('market')
        since = request.args.get('since')  # unix seconds

        if not market:
            return jsonify({"status": "ERROR", "message": "market parameter required"}), 400

        logger.info(f"💰 Getting funding payments for {market}" +
                    (f" since={since}" if since else ""))

        try:
            metadata = await get_market_metadata(market)
            market_id = metadata['market_id']
        except Exception as e:
            return jsonify({"status": "ERROR", "message": f"Market {market} not found"}), 404

        url = f"{BASE_URL}/api/v1/positionFunding"
        params = {
            "account_index": ACCOUNT_INDEX,
            "market_id": market_id,
            "limit": 100
        }

        async with aiohttp.ClientSession() as session:
            async with session.get(url, params=params) as resp:
                if resp.status != 200:
                    return jsonify({"status": "ERROR", "message": f"HTTP {resp.status}"}), 500
                data = await resp.json()

        fundings = data.get("position_fundings", [])
        logger.info(f"   Found {len(fundings)} total funding entries for {market}")

        total_funding = 0.0
        counted = 0

        for f in fundings:
            if since:
                entry_ts = int(f.get("timestamp", 0) or 0)
                if entry_ts < int(since):
                    logger.debug(f"   Skipping old entry: ts={entry_ts} < since={since}")
                    continue

            change = float(f.get("change", 0) or 0)
            discount = float(f.get("discount", 0) or 0)
            net = change + discount
            total_funding += net
            counted += 1
            logger.info(f"   ✅ Entry: ts={f.get('timestamp')}, "
                       f"change={change}, discount={discount}, net={net}")

        logger.info(f"   ✅ Total funding for {market}: ${total_funding:.6f} "
                   f"({counted}/{len(fundings)} entries)")

        return jsonify({
            "status": "OK",
            "accumulated_funding": total_funding,
            "market": market,
            "market_id": market_id,
            "entries_count": counted
        }), 200

    except Exception as e:
        logger.error(f"Funding error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: OPEN POSITION
# ============================================

@app.route('/order/market', methods=['POST'])
async def open_market_position():
    """Открыть рыночную позицию"""
    if not signer_client:
        return jsonify({"status": "ERROR", "message": "Trading not configured"}), 503

    try:
        data = await request.get_json()
        symbol = data.get('market')
        side = data.get('side', 'BUY').upper()
        size_str = data.get('size')
        slippage_pct = float(data.get('price_slippage_pct', 2.0))

        if not symbol or not size_str:
            return jsonify({"status": "ERROR", "message": "market and size required"}), 400

        metadata = await get_market_metadata(symbol)
        market_id = metadata['market_id']
        size_decimals = metadata['size_decimals']
        price_decimals = metadata['price_decimals']

        logger.info(f"📊 {symbol}: id={market_id}, size_dec={size_decimals}, price_dec={price_decimals}")

        symbol_upper = symbol.upper()
        target_leverage = leverage_settings.get(symbol_upper, SYSTEM_MAX_LEVERAGE)
        logger.info(f"   Target leverage: {target_leverage}x")

        size = Decimal(size_str)
        logger.info(f"   Size: {size}")

        mark_price = await get_mark_price(market_id, metadata['symbol'])

        if side == 'BUY':
            worst_price = mark_price * (1 + slippage_pct / 100)
        else:
            worst_price = mark_price * (1 - slippage_pct / 100)

        base_amount_int = int(size * Decimal(10 ** size_decimals))
        price_int = int(Decimal(str(worst_price)) * Decimal(10 ** price_decimals))

        is_ask = side == 'SELL'
        client_order_index = int(datetime.utcnow().timestamp() * 1000) % 2147483647

        logger.info(f"🚀 {side} {size} {symbol} @ {target_leverage}x")
        logger.info(f"   base_amount={base_amount_int}, price={price_int}, mark=${mark_price:.2f}")

        max_retries = 3
        last_error = None

        for attempt in range(max_retries):
            try:
                if attempt > 0:
                    logger.warning(f"   ⚠️ Retry {attempt + 1}/{max_retries}")
                    await refresh_signer_nonce()
                    await asyncio.sleep(1)
                    client_order_index = int(datetime.utcnow().timestamp() * 1000) % 2147483647

                response = await signer_client.create_order(
                    market_index=market_id,
                    client_order_index=client_order_index,
                    base_amount=base_amount_int,
                    price=price_int,
                    is_ask=is_ask,
                    order_type=signer_client.ORDER_TYPE_MARKET,
                    time_in_force=signer_client.ORDER_TIME_IN_FORCE_IMMEDIATE_OR_CANCEL,
                    reduce_only=False,
                    order_expiry=signer_client.DEFAULT_IOC_EXPIRY,
                )

                if isinstance(response, tuple):
                    if len(response) >= 3:
                        tx, resp_obj, err = response[0], response[1], response[2]
                    elif len(response) == 2:
                        resp_obj, err = response[0], response[1]
                    else:
                        resp_obj = response[0]
                        err = None

                    if err is not None:
                        error_msg = err.decode('utf-8') if isinstance(err, bytes) else str(err)

                        if 'nonce' in error_msg.lower() and attempt < max_retries - 1:
                            logger.warning(f"   ⚠️ Nonce error, will retry: {error_msg}")
                            last_error = error_msg
                            continue

                        logger.error(f"   ❌ Order error: {error_msg}")
                        return jsonify({"status": "ERROR", "message": error_msg}), 500

                    if resp_obj:
                        tx_hash_raw = getattr(resp_obj, 'tx_hash', None)
                        tx_hash = ('0x' + tx_hash_raw.hex()) if isinstance(tx_hash_raw, bytes) else str(tx_hash_raw) if tx_hash_raw else None

                        code = getattr(resp_obj, 'code', 200)
                        message = getattr(resp_obj, 'message', '')

                        logger.info(f"   ✅ Order submitted: code={code}, tx_hash={tx_hash}")

                        return jsonify({
                            "status": "success",
                            "client_order_index": client_order_index,
                            "tx_hash": tx_hash,
                            "code": code,
                            "message": message,
                            "market": symbol,
                            "market_id": market_id,
                            "side": side,
                            "size": str(size),
                            "price": f"${mark_price:.2f}",
                            "target_leverage": target_leverage
                        }), 200

                break

            except Exception as e:
                error_msg = str(e)
                if 'nonce' in error_msg.lower() and attempt < max_retries - 1:
                    logger.warning(f"   ⚠️ Exception with nonce: {error_msg}")
                    last_error = error_msg
                    continue
                else:
                    raise

        if last_error:
            logger.error(f"   ❌ All retries failed: {last_error}")
            return jsonify({"status": "ERROR", "message": f"Failed after {max_retries} attempts: {last_error}"}), 500

        return jsonify({
            "status": "submitted",
            "message": "Order likely submitted",
            "market": symbol
        }), 202

    except Exception as e:
        logger.error(f"Order error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: CLOSE POSITION
# ============================================

@app.route('/positions/close', methods=['POST'])
async def close_position():
    """Закрыть позицию"""
    if not signer_client:
        return jsonify({"status": "ERROR", "message": "Trading not configured"}), 503

    try:
        data = await request.get_json()
        symbol = data.get('market')
        current_side = data.get('current_side', '').upper()

        if not symbol or not current_side:
            return jsonify({"status": "ERROR", "message": "market and current_side required"}), 400

        account_api = lighter.AccountApi(api_client)
        raw = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

        if not hasattr(raw, 'accounts') or not raw.accounts:
            return jsonify({"status": "ERROR", "message": "No account found"}), 404

        acc = raw.accounts[0]
        positions = acc.positions or []

        position = None
        for pos in positions:
            pos_symbol = getattr(pos, 'symbol', None)
            pos_sign = getattr(pos, 'sign', 0)

            if pos_sign > 0:
                pos_side = 'LONG'
            elif pos_sign < 0:
                pos_side = 'SHORT'
            else:
                continue

            if pos_symbol:
                market_match = (
                    pos_symbol.upper() == symbol.upper() or
                    pos_symbol.upper().replace('-USDC', '') == symbol.upper()
                )

                if market_match and pos_side == current_side:
                    position = pos
                    break

        if not position:
            available = [
                (getattr(p, 'symbol', '?'),
                 'LONG' if getattr(p, 'sign', 0) > 0 else 'SHORT' if getattr(p, 'sign', 0) < 0 else '?')
                for p in positions
            ]
            return jsonify({
                "status": "ERROR",
                "message": f"No {current_side} position found for {symbol}",
                "available_positions": [f"{s} {side}" for s, side in available]
            }), 404

        size = abs(float(getattr(position, 'position', 0) or 0))

        if size <= 0:
            return jsonify({"status": "ERROR", "message": "Position size is 0"}), 400

        logger.info(f"🔄 Closing {current_side} position: {symbol}, size={size}")

        close_side = 'SELL' if current_side == 'LONG' else 'BUY'

        metadata = await get_market_metadata(symbol)
        market_id = metadata['market_id']
        size_decimals = metadata['size_decimals']
        price_decimals = metadata['price_decimals']

        client_order_index = int(datetime.utcnow().timestamp() * 1000) % 2147483647
        is_ask = close_side == 'SELL'

        real_price = await get_real_market_price(market_id, metadata['symbol'], close_side)

        if real_price is None:
            entry_price = float(getattr(position, 'avg_entry_price', 0) or 0)
            if entry_price > 0:
                real_price = entry_price
                logger.info(f"   📊 Using entry price: ${real_price:.2f}")
            else:
                return jsonify({
                    "status": "ERROR",
                    "message": "Could not determine market price"
                }), 500

        slippage_pct = 10.0
        if close_side == 'BUY':
            worst_price = real_price * (1 + slippage_pct / 100)
        else:
            worst_price = real_price * (1 - slippage_pct / 100)

        base_amount_int = int(Decimal(str(size)) * Decimal(10 ** size_decimals))
        price_int = int(Decimal(str(worst_price)) * Decimal(10 ** price_decimals))

        logger.info(f"   {close_side} {size} {symbol} @ market")
        logger.info(f"   real=${real_price:.2f}, worst=${worst_price:.2f} (slippage={slippage_pct}%)")
        logger.info(f"   base_amount={base_amount_int}, price={price_int}")

        response = await signer_client.create_order(
            market_index=market_id,
            client_order_index=client_order_index,
            base_amount=base_amount_int,
            price=price_int,
            is_ask=is_ask,
            order_type=signer_client.ORDER_TYPE_MARKET,
            time_in_force=signer_client.ORDER_TIME_IN_FORCE_IMMEDIATE_OR_CANCEL,
            reduce_only=True,
            order_expiry=signer_client.DEFAULT_IOC_EXPIRY,
        )

        tx_hash = None
        error_msg = None

        if isinstance(response, tuple) and len(response) >= 2:
            resp_obj = response[1] if len(response) >= 2 else response[0]
            err = response[2] if len(response) >= 3 else None

            if err is not None:
                error_msg = err.decode('utf-8') if isinstance(err, bytes) else str(err)
                logger.error(f"   ❌ Close error: {error_msg}")
            else:
                tx_hash_raw = getattr(resp_obj, 'tx_hash', None)
                if isinstance(tx_hash_raw, bytes):
                    tx_hash = '0x' + tx_hash_raw.hex()
                elif tx_hash_raw:
                    tx_hash = str(tx_hash_raw)

        if error_msg and 'reduce' in error_msg.lower():
            logger.warning(f"   ⚠️ Retrying without reduce_only...")

            client_order_index = int(datetime.utcnow().timestamp() * 1000) % 2147483647

            response = await signer_client.create_order(
                market_index=market_id,
                client_order_index=client_order_index,
                base_amount=base_amount_int,
                price=price_int,
                is_ask=is_ask,
                order_type=signer_client.ORDER_TYPE_MARKET,
                time_in_force=signer_client.ORDER_TIME_IN_FORCE_IMMEDIATE_OR_CANCEL,
                reduce_only=False,
                order_expiry=signer_client.DEFAULT_IOC_EXPIRY,
            )

            if isinstance(response, tuple) and len(response) >= 2:
                resp_obj = response[1]
                err = response[2] if len(response) >= 3 else None

                if err is not None:
                    error_msg = err.decode('utf-8') if isinstance(err, bytes) else str(err)
                    return jsonify({"status": "ERROR", "message": error_msg}), 500

                tx_hash_raw = getattr(resp_obj, 'tx_hash', None)
                if isinstance(tx_hash_raw, bytes):
                    tx_hash = '0x' + tx_hash_raw.hex()
                elif tx_hash_raw:
                    tx_hash = str(tx_hash_raw)

        if error_msg and not tx_hash:
            return jsonify({"status": "ERROR", "message": error_msg}), 500

        logger.info(f"   ✅ Close order submitted, tx_hash={tx_hash}")

        await asyncio.sleep(3)

        raw2 = await account_api.account(by="index", value=str(ACCOUNT_INDEX))
        if hasattr(raw2, 'accounts') and raw2.accounts:
            acc2 = raw2.accounts[0]
            positions2 = acc2.positions or []

            still_open = False
            for pos in positions2:
                pos_symbol = getattr(pos, 'symbol', None)
                pos_sign = getattr(pos, 'sign', 0)
                pos_size = abs(float(getattr(pos, 'position', 0) or 0))

                if pos_symbol == getattr(position, 'symbol', None) and pos_size > 0:
                    if (pos_sign > 0 and current_side == 'LONG') or (pos_sign < 0 and current_side == 'SHORT'):
                        still_open = True
                        logger.warning(f"   ⚠️ Position still open: {pos_size}")
                        break

            if not still_open:
                logger.info(f"   ✅ Position confirmed CLOSED")
                return jsonify({
                    "status": "success",
                    "message": "Position closed successfully",
                    "market": symbol,
                    "size": str(size),
                    "side": close_side,
                    "tx_hash": tx_hash
                }), 200

        return jsonify({
            "status": "submitted",
            "message": "Close order submitted but position may still be open",
            "market": symbol,
            "size": str(size),
            "side": close_side,
            "tx_hash": tx_hash,
            "note": "Check /positions to verify"
        }), 200

    except Exception as e:
        logger.error(f"Close error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: MAX LEVERAGE
# ============================================

@app.route('/market/<symbol>/max-leverage', methods=['GET'])
async def get_max_leverage(symbol: str):
    """Получить max leverage для рынка с правильным приоритетом"""
    try:
        symbol_upper = symbol.upper().replace('-USDC', '').replace('/USDC', '')

        logger.info(f"🎚️ Getting max leverage for {symbol} ({symbol_upper})")

        # 1. ХАРДКОД — самый высокий приоритет (всегда доверяем ему больше, чем кэшу)
        if symbol_upper in KNOWN_MAX_LEVERAGE:
            hardcoded = KNOWN_MAX_LEVERAGE[symbol_upper]
            logger.info(f"   ✅ From HARDCODE: {hardcoded}x")
            # Можно обновить кэш, чтобы в следующий раз быстрее
            MAX_LEVERAGE_CACHE[symbol_upper] = hardcoded
            return jsonify({
                "status": "OK",
                "market": symbol,
                "max_leverage": hardcoded,
                "source": "hardcoded (priority)"
            }), 200

        # 2. КЭШ — только если хардкода нет
        if symbol_upper in MAX_LEVERAGE_CACHE:
            cached = MAX_LEVERAGE_CACHE[symbol_upper]
            logger.info(f"   ✅ From CACHE: {cached}x")
            return jsonify({
                "status": "OK",
                "market": symbol,
                "max_leverage": cached,
                "source": "cache"
            }), 200

        # 3. Позиция (если есть открытая)
        try:
            account_api = lighter.AccountApi(api_client)
            raw = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

            if hasattr(raw, 'accounts') and raw.accounts:
                positions = raw.accounts[0].positions or []
                for pos in positions:
                    pos_symbol = (getattr(pos, 'symbol', '') or '').upper()
                    clean_pos = pos_symbol.replace('-USDC', '').replace('/USDC', '')

                    if clean_pos == symbol_upper:
                        imf = float(getattr(pos, 'initial_margin_fraction', 0) or 0)
                        if imf > 0:
                            real_leverage = int(round(100 / imf))
                            MAX_LEVERAGE_CACHE[symbol_upper] = real_leverage
                            logger.info(f"   ✅ From OPEN POSITION: {real_leverage}x (IMF={imf})")
                            return jsonify({
                                "status": "OK",
                                "market": symbol,
                                "max_leverage": real_leverage,
                                "source": "open_position"
                            }), 200
        except Exception as pos_err:
            logger.debug(f"   Failed to check open position: {pos_err}")

        # 4. Дефолт, если ничего нет
        default_leverage = 3
        MAX_LEVERAGE_CACHE[symbol_upper] = default_leverage
        logger.info(f"   ⚠️ Using DEFAULT: {default_leverage}x")
        return jsonify({
            "status": "OK",
            "market": symbol,
            "max_leverage": default_leverage,
            "source": "default"
        }), 200

    except Exception as e:
        logger.error(f"Max leverage error for {symbol}: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: SET LEVERAGE
# ============================================

@app.route('/user/leverage', methods=['POST'])
async def set_user_leverage():
    """Установить leverage для рынка"""
    if not signer_client:
        return jsonify({"status": "ERROR", "message": "Trading not configured"}), 503

    try:
        data = await request.get_json()
        symbol = data.get('market')
        leverage = int(data.get('leverage', 1))

        if not symbol:
            return jsonify({"status": "ERROR", "message": "market required"}), 400

        if leverage < 1 or leverage > 20:
            return jsonify({"status": "ERROR", "message": "leverage must be 1-20"}), 400

        metadata = await get_market_metadata(symbol)
        market_id = metadata['market_id']

        symbol_upper = symbol.upper()
        max_lev = MAX_LEVERAGE_CACHE.get(symbol_upper, SYSTEM_MAX_LEVERAGE)
        actual_leverage = min(leverage, max_lev)

        if actual_leverage != leverage:
            logger.warning(f"   ⚠️ Requested {leverage}x > max {max_lev}x for {symbol}, using {actual_leverage}x")

        logger.info(f"🎚️ Setting leverage for {symbol}: {actual_leverage}x (requested: {leverage}x)")
        logger.info(f"   Market ID: {market_id}, fraction: {10000 // actual_leverage}")

        leverage_fraction = 10000 // actual_leverage

        tx_type, tx_info, tx_hash, error = signer_client.sign_update_leverage(
            market_id,
            leverage_fraction,
            signer_client.CROSS_MARGIN_MODE,
        )

        if error:
            error_msg = error.decode('utf-8') if isinstance(error, bytes) else str(error)
            logger.error(f"   ❌ Leverage error: {error_msg}")
            return jsonify({"status": "ERROR", "message": error_msg}), 500

        result = await signer_client.tx_api.send_tx(tx_type=tx_type, tx_info=tx_info)

        if result and hasattr(result, 'code') and result.code == 200:
            tx_hash_hex = tx_hash.decode('utf-8') if isinstance(tx_hash, bytes) else str(tx_hash)

            # ✅ НОВОЕ: кэшируем подтверждённый leverage
            MAX_LEVERAGE_CACHE[symbol_upper] = actual_leverage
            leverage_settings[symbol_upper] = actual_leverage
            logger.info(f"   📦 Cached leverage: {symbol_upper} = {actual_leverage}x")

            logger.info(f"   ✅ Leverage set: {actual_leverage}x, tx_hash={tx_hash_hex}")

            return jsonify({
                "status": "success",
                "message": f"Leverage set to {actual_leverage}x for {symbol}",
                "market": symbol,
                "market_id": market_id,
                "leverage": actual_leverage,
                "requested_leverage": leverage,
                "fraction": leverage_fraction,
                "method": "native",
                "tx_hash": tx_hash_hex
            }), 200

        error_msg = getattr(result, 'message', 'Unknown error')
        logger.error(f"   ❌ TX failed: {error_msg}")
        return jsonify({"status": "ERROR", "message": str(error_msg)}), 500

    except Exception as e:
        logger.error(f"Leverage error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: ORDERBOOK
# ============================================

@app.route('/market/<symbol>/orderbook', methods=['GET'])
async def get_orderbook(symbol: str):
    """Получить orderbook для рынка"""
    try:
        limit = int(request.args.get('limit', 10))

        metadata = await get_market_metadata(symbol)
        market_id = metadata['market_id']

        logger.info(f"📖 Getting orderbook for {symbol} (id={market_id}), limit={limit}")

        order_api = lighter.OrderApi(api_client)

        orderbook = await asyncio.wait_for(
            order_api.order_book_orders(market_id=market_id, limit=limit),
            timeout=10.0
        )

        if not orderbook:
            return jsonify({"status": "ERROR", "message": "No orderbook data"}), 404

        bids = []
        asks = []

        if hasattr(orderbook, 'bids'):
            for bid in orderbook.bids[:limit]:
                if isinstance(bid, list) or isinstance(bid, tuple):
                    bids.append({"price": str(bid[0]), "size": str(bid[1])})
                else:
                    bids.append({
                        "price": str(getattr(bid, 'price', 0)),
                        "size": str(getattr(bid, 'remaining_base_amount', 0))
                    })

        if hasattr(orderbook, 'asks'):
            for ask in orderbook.asks[:limit]:
                if isinstance(ask, list) or isinstance(ask, tuple):
                    asks.append({"price": str(ask[0]), "size": str(ask[1])})
                else:
                    asks.append({
                        "price": str(getattr(ask, 'price', 0)),
                        "size": str(getattr(ask, 'remaining_base_amount', 0))
                    })

        logger.info(f"   ✅ Orderbook: {len(bids)} bids, {len(asks)} asks")

        best_bid = float(bids[0]["price"]) if bids else None
        best_ask = float(asks[0]["price"]) if asks else None
        mid_price = (best_bid + best_ask) / 2 if best_bid and best_ask else None
        spread = (best_ask - best_bid) if best_bid and best_ask else None
        spread_bps = (spread / best_bid * 10000) if spread and best_bid else None

        return jsonify({
            "status": "OK",
            "market": symbol,
            "market_id": market_id,
            "bids": bids,
            "asks": asks,
            "summary": {
                "best_bid": best_bid,
                "best_ask": best_ask,
                "mid_price": mid_price,
                "spread": spread,
                "spread_bps": spread_bps,
                "bids_count": len(bids),
                "asks_count": len(asks)
            },
            "timestamp": datetime.utcnow().isoformat()
        }), 200

    except Exception as e:
        logger.error(f"Orderbook error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: CALCULATE SIZE
# ============================================

@app.route('/market/<symbol>/calculate-size', methods=['POST'])
async def calculate_max_size(symbol: str):
    """Рассчитать максимальный размер позиции"""
    try:
        data = await request.get_json()
        margin_usd = float(data.get('margin_usd', 0))
        leverage = int(data.get('leverage', 1))
        is_buy = data.get('is_buy', True)

        if margin_usd <= 0:
            return jsonify({"status": "ERROR", "message": "margin_usd must be positive"}), 400

        metadata = await get_market_metadata(symbol)
        market_id = metadata['market_id']

        logger.info(f"💰 Calculating max size for {symbol}: margin=${margin_usd}, leverage={leverage}x")

        order_api = lighter.OrderApi(api_client)

        orderbook = await asyncio.wait_for(
            order_api.order_book_orders(market_id=market_id, limit=1),
            timeout=5.0
        )

        price = 0
        if orderbook and hasattr(orderbook, 'bids') and hasattr(orderbook, 'asks'):
            bids = orderbook.bids
            asks = orderbook.asks

            if bids and asks and len(bids) > 0 and len(asks) > 0:
                if isinstance(bids[0], list):
                    best_bid = float(bids[0][0])
                    best_ask = float(asks[0][0])
                else:
                    best_bid = float(getattr(bids[0], 'price', 0))
                    best_ask = float(getattr(asks[0], 'price', 0))

                price = best_ask if is_buy else best_bid
                logger.info(f"   Using price: ${price:.4f} ({'ask' if is_buy else 'bid'})")

        if price <= 0:
            logger.warning(f"   ⚠️ Could not get orderbook, using mark price")
            price = await get_mark_price(market_id, symbol)

        position_value = margin_usd * leverage
        max_size = position_value / price

        size_decimals = metadata['size_decimals']
        max_size_rounded = round(max_size, size_decimals)

        logger.info(f"   ✅ Max size: {max_size_rounded:.{size_decimals}f} {symbol} (value: ${position_value:.2f})")

        return jsonify({
            "status": "OK",
            "market": symbol,
            "max_size": f"{max_size_rounded:.{size_decimals}f}",
            "price": f"{price:.4f}",
            "position_value": f"{position_value:.2f}",
            "leverage": leverage
        }), 200

    except Exception as e:
        logger.error(f"Calculate size error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500


# ============================================
# ENDPOINT: DEBUG POSITION RAW FIELDS
# ============================================

@app.route('/debug/position/<string:symbol>', methods=['GET'])
async def debug_position_raw(symbol: str):
    """Дебаг: показывает все сырые поля позиции + implied mark price"""
    try:
        account_api = lighter.AccountApi(api_client)
        raw = await account_api.account(by="index", value=str(ACCOUNT_INDEX))

        if not hasattr(raw, 'accounts') or not raw.accounts:
            return jsonify({"status": "ERROR", "message": "No account found"}), 404

        positions = raw.accounts[0].positions or []

        results = []
        for pos in positions:
            pos_symbol = (getattr(pos, 'symbol', '') or '').upper()
            clean = pos_symbol.replace('-USDC', '').replace('/USDC', '')

            if clean != symbol.upper() and pos_symbol != symbol.upper():
                continue

            size = float(getattr(pos, 'position', 0) or 0)
            pos_value = float(getattr(pos, 'position_value', 0) or 0)
            implied_mark = pos_value / abs(size) if size != 0 else None

            entry_price = float(getattr(pos, 'avg_entry_price', 0) or 0)
            expected_value_by_entry = abs(size) * entry_price

            results.append({
                "symbol": pos_symbol,
                "sign": getattr(pos, 'sign', None),
                "position (size)": size,
                "avg_entry_price": entry_price,
                "position_value": pos_value,
                "expected_value_by_entry (size * entry)": round(expected_value_by_entry, 4),
                "implied_mark_price (value / size)": round(implied_mark, 4) if implied_mark else None,
                "unrealized_pnl": getattr(pos, 'unrealized_pnl', None),
                "realized_pnl": getattr(pos, 'realized_pnl', None),
                "liquidation_price": getattr(pos, 'liquidation_price', None),
                "total_funding_paid_out": getattr(pos, 'total_funding_paid_out', None),
                "initial_margin_fraction": getattr(pos, 'initial_margin_fraction', None),
                "allocated_margin": getattr(pos, 'allocated_margin', None),
            })

        if not results:
            return jsonify({
                "status": "ERROR",
                "message": f"No position found for {symbol}",
                "available": [(getattr(p, 'symbol', '?')) for p in positions]
            }), 404

        return jsonify({"status": "OK", "data": results}), 200

    except Exception as e:
        logger.error(f"Debug position error: {e}", exc_info=True)
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route('/debug/api-methods', methods=['GET'])
async def debug_api_methods():
    import lighter
    result = {}
    for cls_name in ['OrderApi', 'AccountApi', 'CandlestickApi']:
        cls = getattr(lighter, cls_name, None)
        if cls:
            instance = cls(api_client)
            # только публичные методы без служебных
            result[cls_name] = [m for m in dir(instance)
                               if not m.startswith('_') and callable(getattr(instance, m))]
    return jsonify(result)


# ============================================
# RUN
# ============================================

if __name__ == '__main__':
    import hypercorn.asyncio
    config = hypercorn.Config()
    config.bind = ["0.0.0.0:5001"]
    asyncio.run(hypercorn.asyncio.serve(app, config))
