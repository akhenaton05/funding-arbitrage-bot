# Funding Rates Tracker Bot

[![Docker](https://img.shields.io/badge/Docker-Production-blue.svg)](https://www.docker.com/)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg)](https://spring.io/projects/spring-boot)
[![Telegram](https://img.shields.io/badge/Telegram-Notifications-purple.svg)](https://core.telegram.org/bots/api)

**Funding Rates Tracker Bot** — автоматизированная система для арбитража funding rates между биржами **Extended** и **Aster**. Бот мониторит разницу в funding rates, автоматически открывает хеджированные позиции, валидирует их открытие и закрывает после получения funding платежей.

## 🎯 Ключевые возможности

- 🔍 **Автоматический мониторинг** 50+ торговых пар
- ⚡ **Параллельное открытие** LONG/SHORT позиций (<3 сек)
- ✅ **Двусторонняя валидация** через API обеих бирж
- 🚨 **Emergency close** при ошибках открытия
- 📱 **Telegram уведомления** в реальном времени
- 🔄 **Автозапуск** Docker + systemd
- 💰 **Расчет P&L** каждой сделки

## 🏗️ Архитектура

Общая схема работы
Система состоит из 2-х независимых сервисов, которые общаются через HTTP API:
``text
Java Bot (Spring Boot)  ↔ HTTP API ↔ Extended Service (Python + Docker)
       ↓                                         ↓
Aster Exchange API                    Extended Exchange API
``
1. Java Bot (основная логика) — Spring Boot + Systemd
Что делает:

Сканирует funding rates каждые 2 минуты

Находит арбитражные возможности (разница > 0.05%)

Отправляет команды на открытие позиций

Валидирует что позиции открылись на обеих биржах

Отправляет уведомления в Telegram

Закрывает позиции после funding payment

Компоненты:

text
FundingRateService  → сканирует рейты Aster + Extended
FundingBot          → открывает/закрывает позиции  
PositionValidator   → проверяет что позиции реально открылись
TelegramService     → уведомления в чат
2. Extended Service (Python + Flask + Docker)
Что делает:

Работает как прокси к Extended Exchange API

Принимает команды от Java бота: /positions, /balance, /open, /close

Подписывает запросы Ed25519 (Extended требует)

Кэширует market data (5 минут)

Доступен на localhost:5000

Почему отдельно:

Extended API требует Ed25519 подписи (сложно в Java)

Docker контейнер — изоляция + лёгкое обновление

Python проще для криптографии

3. Поток данных (последовательность работы)
text
1. TIMER (2 мин) → FundingRateService сканирует 50+ пар
2. FundingRateService: "RESOLVE: Extended +0.05%, Aster -0.03%" 
3. FundingBot → ExtendedService: POST /open RESOLVE SHORT $100 10x
4. FundingBot → Aster API: POST /order RESOLVE LONG $100 10x  
5. PositionValidator → Extended: GET /positions?market=RESOLVE&side=SHORT
6. PositionValidator → Aster: GET /positionRisk?symbol=RESOLVEUSDT
7. ✅ Обе позиции открыты → Telegram: "Positions opened!"
8. Funding time → FundingBot закрывает обе позиции
9. Telegram: "Closed. Profit +$0.04"
4. Критические особенности
Параллельное открытие позиций
java
CompletableFuture.extFuture = open Extended
CompletableFuture.astFuture = open Aster  
CompletableFuture.allOf().get(60s)  // 60 сек таймаут
Двусторонняя валидация
text
Extended API: возвращает только реальные позиции
Aster API (Hedge): возвращает 2 позиции (LONG+SHORT), фильтр по positionAmt > 0.001
Emergency close
text
Если Extended не открыл → закрываем Aster  
Если Aster не открыл → закрываем Extended
5. Развёртывание на сервере
text
Ubuntu Server
├── Docker (Extended Service)
│   └── localhost:5000
├── systemd (Java Bot)
│   └── localhost:8080 (management)
└── Telegram Bot (уведомления)
Автозапуск:

Docker: restart: always

Systemd: Restart=on-failure

6. Поток логов при сделке
text
[21:24] FundingRateService: Scanning 52 markets...
[21:25] FundingBot: RESOLVE arbitrage 0.08% (>0.05%)
[21:25] Extended: Order ext_12345 created
[21:25] Aster: Order ast_67890 created  
[21:25] Validator: Extended position size=941.0 OK
[21:25] Validator: Aster positionAmt=941.0 OK
[21:25] Telegram: ✅ RESOLVE positions opened
[22:00] FundingBot: Funding time, closing...
[22:00] Extended: Position closed
[22:00] Aster: Position closed
[22:00] Telegram: 💰 Profit +$0.04
7. Безопасность и надёжность
text
✅ Docker изоляция Extended сервиса
✅ Systemd автоперезапуск Java бота
✅ Параллельное открытие (<3 сек)
✅ Валидация обеих позиций
✅ Emergency close при ошибках
✅ Telegram алерты о проблемах
✅ Логирование всех операций
✅ IP whitelist для API ключей
Итог: Полностью автоматическая система 24/7 с мониторингом и восстановлением после сбоев.



Для работы приложения необходимо создать файл application.yaml в resources для работы Aster Api + файл .env для работы Extended Api - для совершения сделок необходимы ключи(apiKeys)

👨‍💻 Автор
akhenaton05 - GitHub

⚠️ Disclaimer: Торговля криптовалютами сопряжена с высокими рисками. Тестируйте на демо-счетах. Автор не несет ответственности за финансовые потери.
