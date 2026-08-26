# Bastion

Античит + антикраш + антилаг для Paper 1.20.x (Java 17).

## Сборка

Залей папку в репозиторий на GitHub — Actions соберут jar сами
(`.github/workflows/build.yml`). Готовый файл лежит во вкладке
**Actions → последний запуск → Artifacts → Bastion**.

Кидаешь `Bastion.jar` в `plugins/`, перезапускаешь сервер, правишь `plugins/Bastion/config.yml`.

## Команды

| Команда | Что делает |
|---|---|
| `/bastion reload` | перечитать конфиг |
| `/bastion alerts` | вкл/выкл алерты себе |
| `/bastion info <ник>` | клиент, версия, каналы модов, ПВП-метка |
| `/bastion vl <ник>` | текущие VL по всем проверкам |
| `/bastion exempt <ник>` | временно вывести игрока из проверок |

Права: `bastion.command`, `bastion.alerts`, `bastion.bypass`.

## Что ловит

**Движение** — Flight, InfinityJump, HighJump, LongJump, Speed, Timer, Jesus, Spider,
Phase (проход сквозь стены), NoFall, Step, AntiVoid, ClickTP/Blink, GuiMove, NoSlow,
Velocity, ReverseStep, ElytraFly, ElytraBoost, BoatFly, EntitySpeed, EntityControl,
MountBypass, Anchor.

**Бой** — KillAura (угол атаки), Aimbot (рывки камеры + GCD), Reach (с компенсацией пинга),
HitBox (луч взгляда против AABB), AutoClicker (CPS + дисперсия интервалов), MultiAura,
Criticals, AutoTotem, ArrowSpam, ArrowDodge, FlameThrower, AutoAnvil, AutoBed, AutoCrystal.

**Блоки** — Scaffold, AirPlace, Tower, Surround, SelfTrap/SelfWeb, Burrow, LiquidPlace,
Nuker, VeinMiner, SpeedMine, PacketMine.

**Предметы** — FastUse, AutoReplenish, ChestBypass (сундук сквозь блоки), ChestStealer,
InventoryMove, PotionSpoof.

**Спам и краш** — ChatSpam, CommandSpam, PacketSpam (netty-лимиты по типам пакетов),
BookCrash, SignCrash, ItemCrash (NBT/стаки/зачарования), OffhandCrasher, InvalidPacket
(NaN/Infinity, pitch > 90), KeepAliveSpoof.

**Лаг-машины** — лимиты сущностей/дропа/TNT/падающих блоков/вагонеток/армор-стендов на чанк,
детект редстоун-часов с заморозкой чанка, троттлинг хопперов, обрезка цепных взрывов и
аномальных радиусов, гигантские порталы, спам порталами, спам генерацией чанков, автоочистка дропа.

## Режим ПВП

При ударе игрока обоим вешается босс-бар «Режим ПВП» на 30 секунд.
Пока метка активна: телепорты (перл, команда, плагин, хорус) блокируются,
команды из `pvp-tag.blocked-commands` не работают, а выход из игры = смерть на месте
с выпадением вещей и сообщением в чат.

## Зачарование «Перо»

Проверка NoFall пропускает игрока, если на ботинках есть кастомное зачарование:
либо PDC-ключ `bastion:feather`, либо слово «Перо» в лоре/названии.
Настраивается в `custom-enchants.feather`.

Пример выдачи:
```
/give @p netherite_boots{display:{Lore:['{"text":"Перо I","color":"gray","italic":false}']}} 1
```

## Что честно НЕ ловится поведением

- **ESP / StorageESP / X-Ray / TimeChanger / Freecam-рендер** — это чтение данных, которые
  сервер уже отправил клиенту. Плагин их не «увидит».
  Реальная защита — резать данные на отправке:
  в `paper-world-defaults.yml` включи `anticheat.anti-xray.enabled: true`
  и `engine-mode: 2` со своим списком `hidden-blocks`.
  В плагине есть только поведенческая эвристика `XRAY` (доля руды к породе + вскрытие
  руды из сплошного массива) — она даёт наводку, а не доказательство, поэтому по
  умолчанию `max-vl: 0` (только алерт, без кика).
- **Аппаратные макросы и читы «в пределах человеческого»** — ловятся только статистикой,
  поэтому пороги `combat` и `movement` лучше подкручивать под свой сервер.

## Настройка чувствительности

Все пороги — в `config.yml`. Если на сервере лагает или высокий пинг, поднимай:
`movement.max-sprint-speed`, `combat.reach-lag-compensation`, `blocks.min-break-time-ratio`
(меньше = мягче), и `max-vl` у шумных проверок. `max-vl: 0` = проверка только пишет алерт.

Первую неделю рекомендую поставить всем проверкам `max-vl: 0`, посмотреть логи
и уже потом включать кики — иначе выкосишь живых игроков ложняками.
