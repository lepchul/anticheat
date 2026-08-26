package ru.lepchul.bastion.check;

public enum CheckType {

    // ---------- движение ----------
    FLIGHT("Flight", "Полёт"),
    INFINITY_JUMP("InfinityJump", "Бесконечный прыжок"),
    HIGH_JUMP("HighJump", "Высокий прыжок"),
    LONG_JUMP("LongJump", "Длинный прыжок"),
    SPEED("Speed", "Ускорение"),
    TIMER("Timer", "Ускорение тикрейта"),
    JESUS("Jesus", "Ходьба по воде"),
    SPIDER("Spider", "Лазание по стенам"),
    PHASE("Phase", "Проход сквозь блоки"),
    NOFALL("NoFall", "Отмена урона от падения"),
    STEP("Step", "Подъём без прыжка"),
    ANTI_VOID("AntiVoid", "Спасение из бездны"),
    CLICK_TP("ClickTP", "Телепорт/блинк"),
    GUI_MOVE("GuiMove", "Движение в инвентаре"),
    NO_SLOW("NoSlow", "Отмена замедления"),
    VELOCITY("Velocity", "Отмена отбрасывания"),
    REVERSE_STEP("ReverseStep", "Обратный шаг"),
    ELYTRA_FLY("ElytraFly", "Читерский элитр"),
    ELYTRA_BOOST("ElytraBoost", "Спам фейерверков"),
    BOAT_FLY("BoatFly", "Полёт на лодке"),
    ENTITY_SPEED("EntitySpeed", "Ускорение транспорта"),
    ENTITY_CONTROL("EntityControl", "Контроль чужого моба"),
    MOUNT_BYPASS("MountBypass", "Обход посадки"),
    ANCHOR("Anchor", "Абуз якоря"),

    // ---------- бой ----------
    KILLAURA("KillAura", "Килл-аура"),
    AIMBOT("Aimbot", "Аимбот"),
    REACH("Reach", "Увеличенная дальность"),
    HITBOX("HitBox", "Увеличенный хитбокс"),
    AUTOCLICKER("AutoClicker", "Автокликер"),
    MULTIAURA("MultiAura", "Атака нескольких целей"),
    CRITICALS("Criticals", "Фейковые криты"),
    AUTO_TOTEM("AutoTotem", "Автототем"),
    ARROW_SPAM("ArrowSpam", "Спам стрелами"),
    ARROW_DODGE("ArrowDodge", "Уклонение от стрел"),
    FLAMETHROWER("FlameThrower", "Спам поджигом"),
    AUTO_ANVIL("AutoAnvil", "Автонаковальня"),
    AUTO_BED("AutoBed", "Автокровать"),
    AUTO_CRYSTAL("AutoCrystal", "Автокристалл"),

    // ---------- блоки ----------
    SCAFFOLD("Scaffold", "Автомост"),
    AIR_PLACE("AirPlace", "Блоки в воздухе"),
    TOWER("Tower", "Быстрая башня"),
    SURROUND("Surround", "Обкладка блоками"),
    SELF_TRAP("SelfTrap", "Самозапирание"),
    BURROW("Burrow", "Заход в блок"),
    LIQUID_PLACE("LiquidPlace", "Блоки на воде без опоры"),
    NUKER("Nuker", "Нюкер"),
    VEIN_MINER("VeinMiner", "Вейн-майнер"),
    SPEED_MINE("SpeedMine", "Быстрое ломание"),
    PACKET_MINE("PacketMine", "Ломание пакетами"),

    // ---------- предметы / инвентарь ----------
    FAST_USE("FastUse", "Быстрое использование"),
    AUTO_REPLENISH("AutoReplenish", "Автопополнение"),
    CHEST_BYPASS("ChestBypass", "Сундук сквозь блоки"),
    CHEST_STEALER("ChestStealer", "Автовынос сундука"),
    INVENTORY_MOVE("InventoryMove", "Действия в инвентаре"),
    POTION_SPOOF("PotionSpoof", "Подмена эффектов"),
    XRAY("XRay", "Подозрительная копка"),

    // ---------- спам / краш ----------
    CHAT_SPAM("ChatSpam", "Спам в чате"),
    COMMAND_SPAM("CommandSpam", "Спам командами"),
    PACKET_SPAM("PacketSpam", "Флуд пакетами"),
    BOOK_CRASH("BookCrash", "Краш книгой"),
    SIGN_CRASH("SignCrash", "Краш табличкой"),
    ITEM_CRASH("ItemCrash", "Краш предметом"),
    OFFHAND_CRASH("OffhandCrash", "Краш второй рукой"),
    INVALID_PACKET("InvalidPacket", "Некорректный пакет"),
    KEEPALIVE_SPOOF("KeepAliveSpoof", "Подмена keep-alive"),
    LAG_MACHINE("LagMachine", "Лаг-машина");

    private final String id;
    private final String ru;

    CheckType(String id, String ru) {
        this.id = id;
        this.ru = ru;
    }

    public String id() { return id; }
    public String ru() { return ru; }
}
