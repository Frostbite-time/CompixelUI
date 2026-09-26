# 菜单同步

[English](../en/menu-sync.md) · [文档目录](../README.md)

声明服务端菜单暴露的状态，再用类型化请求申请修改。Compose MC 负责有界批处理、分片、确认与完整客户端发布。物品归属、权限、持久化和资源语义仍由消费者负责。

**客户端与服务端都要安装**匹配版本的 Compose MC，并声明 BOTH 侧依赖。UI 类保留在客户端入口。共享 `menu-sync` 核心为无依赖的 Java 17 模块，游戏网络逻辑位于适配器。本页示例使用 NeoForge 1.21.1。

## 声明同步菜单

在现有 `AbstractContainerMenu` 上实现 `SyncedMenu` 并持有一个绑定。下面的抽象示例将原生槽位行为与业务操作留给具体菜单：

```java
import dev.composemc.sync.SyncCodecs;
import dev.composemc.sync.SyncSchema;
import dev.composemc.forge.sync.MenuAction;
import dev.composemc.forge.sync.MenuSync;
import dev.composemc.forge.sync.SyncedMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

abstract class PowerMenu extends AbstractContainerMenu implements SyncedMenu {
    private long energy;
    private boolean enabled;

    private static final SyncSchema<PowerMenu> SCHEMA =
        SyncSchema.<PowerMenu>builder("example:power", 1)
            .field("energy", SyncCodecs.LONG, m -> m.energy, (m, v) -> m.energy = v)
            .field("enabled", SyncCodecs.BOOLEAN, m -> m.enabled, (m, v) -> m.enabled = v)
            .build();

    private static final MenuAction<PowerMenu, Boolean> SET_ENABLED = MenuAction.of(
        "set.enabled", SyncCodecs.BOOLEAN,
        (menu, player, value) -> menu.mayConfigure(player) && menu.applyEnabled(value));

    private final MenuSync<PowerMenu> sync = MenuSync.bind(this, SCHEMA).action(SET_ENABLED);

    protected PowerMenu(MenuType<?> type, int id) { super(type, id); }
    @Override public MenuSync<?> menuSync() { return sync; }

    public void captureMachine(long energy, boolean enabled) {
        this.energy = energy;
        this.enabled = enabled;
    }
    public boolean requestEnabled(boolean value) { return sync.request(SET_ENABLED, value).queued(); }

    protected abstract boolean mayConfigure(ServerPlayer player);
    protected abstract boolean applyEnabled(boolean value);
}
```

服务端业务更新时，在菜单广播变化前调用 `captureMachine`。实现 `applyEnabled` 时校验并持久化请求的修改。原生菜单生命周期会启动同步，不需要再创建另一条快照通道。客户端控制器在游戏线程调用 `requestEnabled`，例如处理 `UiBinding` 动作时。返回 Boolean 只表示**已入队**，不表示已经执行。

## 快照约定

Getter 必须返回非空不可变值，客户端初始值也必须有效。替换变化值并复用未变化引用，不支持以原地修改作为变化信号。Setter 在完整更新到达后于客户端游戏线程执行，应只做直接赋值。Setter 失败时框架会尝试恢复旧值，但无法回滚任意外部副作用。

`keyedCollection` 处理带稳定唯一键的不可变列表，同步成员与值，不同步仅迭代顺序的变化。需要业务顺序时显式传输顺序字段，或在客户端排序。大列表发生变化时，捕获和列表生成仍有 O(n) 工作量。

对于频繁发生小变化的大集合，使用 `SyncMap` 和 `keyedMap`：

```java
import dev.composemc.sync.*;

final class CatalogState {
    record Entry(int id, String name) {}
    private SyncMap<Integer, Entry> entries = SyncMap.empty();
    private static final SyncCodec<String> NAME = SyncCodecs.string(256);
    private static final SyncCodec<Entry> ENTRY = SyncCodec.of("example:entry/1",
        (out, entry) -> { out.writeInt(entry.id()); NAME.write(out, entry.name()); },
        in -> new Entry(in.readInt(), NAME.read(in)));

    static final SyncSchema<CatalogState> SCHEMA = SyncSchema.<CatalogState>builder("example:catalog", 1)
        .keyedMap("entries", SyncCodecs.INT, ENTRY, Entry::id,
            m -> m.entries, (m, values) -> m.entries = values)
        .build();

    void upsert(int id, String name) { entries = entries.with(id, new Entry(id, name)); }
    void remove(int id) { entries = entries.without(id); }
}
```

请保留返回的新映射，旧快照不会改变。共享树分支减少差异比较与复制，相同哈希的键使用冲突桶处理。迭代顺序不作保证。`current.forEachChange(previous, handler)` 报告新增、修改和删除；插入或删除时 `before` 或 `after` 为 null。高效更新派生搜索、排序索引仍由消费者负责。

`include(prefix, schema)` 及其投影重载可组合声明，包含的身份和版本参与握手。在会话启动前注册 `onUpdate(menu -> …)`，可在全部字段提交后派生视图。它在最后确认之前执行，不会针对部分批次调用，也不是回滚事务。

字段索引使用 32 位帧格式，不再设置固定的 64 字段上限。标识符语法仍有明确约定：schema 与 codec ID 最多 128 字符，字段/动作名最多 64，组合前缀最多 32。字段和动作名使用字母、数字、`_`、`.`、`-`，schema ID 使用小写的 `namespace:path`。这些是协议身份，与界面翻译标签相互独立。内置枚举 codec 表示最多 256 个常量，更大的取值空间可以使用自定义 codec。

## 编解码与原生值

内置类型包括 `INT`、`LONG`、`BOOLEAN`、`UUID`、`string(maxUtf8Bytes)` 和 `enumeration(EnumClass.class)`。自定义 `SyncCodec.of(id, writer, reader)` 使用 `DataInput` / `DataOutput`，分配前应校验长度和值。字符串限制按 UTF-8 字节计数，协议含义变化时需修改 codec 身份或 schema 版本。

现代适配器通过 `MinecraftSyncCodecs.registry(id, maxBytes, registrySupplier, streamCodec)` 处理需要注册表的原生值。应在所属游戏线程使用，并让记录/动作预算覆盖封装开销。Forge 1.20.1 则提供基于 `FriendlyByteBuf` 的 `MinecraftSyncCodecs.buffer(id, maxBytes, PacketCodec<T>)`。这些辅助 API 限制序列化大小，不会取消原生 codec 自身的约束。

## 动作与权限

菜单打开前，在两端注册匹配的动作 ID 和 codec。适配器校验活跃菜单身份、会话令牌、序列号、初始确认、玩家存活、旁观状态和 `stillValid`。执行器仍须在修改前检查权限、归属及业务约束。动作注册不再有固定的 32 项上限。

`request(action, value)` 返回 `ActionSubmission`。`queued()` 表示本地队列已接收请求，传输与服务端执行仍可能尚未完成。拒绝结果包含 `failure()`、`actual()` 和 `limit()`，序列号为零，没有发出数据，也不消耗序列号。已入队动作保持 FIFO 顺序，不会被静默覆盖或自动重放。

| 拒绝原因 | 含义与处理方式 |
| --- | --- |
| `QUEUE_BYTES` | 保留的待发送正文达到消费者字节预算；等待或提高配置。 |
| `PENDING_ACTIONS` | 尚未收到结果的请求过多；等待回复。 |
| `BODY_BYTES` | 编码超过该动作声明的最大值；调整数据或动作声明。 |
| `CODEC` | Codec 拒绝了值，可能触及其独立大小限制；查看结果详情或日志。 |
| `NOT_READY`、`CLOSED`、`TRANSPORT_UNAVAILABLE`、`UNKNOWN_ACTION` | 菜单、连接或动作当前无法接收请求。 |

在调用处处理拒绝，不要直接假定点击已经成功。下面的辅助方法接受消费者自己的反馈回调，并向调用者保留完整结果：

```java
import dev.composemc.forge.sync.MenuAction;
import dev.composemc.forge.sync.MenuSync;
import dev.composemc.sync.ActionSubmission;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.function.Consumer;

final class ActionRequests {
    static <M extends AbstractContainerMenu, V> ActionSubmission submit(
            MenuSync<M> sync, MenuAction<? super M, V> action, V value,
            Consumer<ActionSubmission> showRefusal) {
        var result = sync.request(action, value);
        if (!result.queued()) showRefusal.accept(result);
        return result;
    }
}
```

根据 `failure()` 选择本地化提示，需要时展示 `actual()` 和 `limit()`，同时通过 `onActionResult` 接收最终处理结果。队列满不会清除较早请求，本库也不会保留被拒绝的点击供以后自动重试。

大小按 Minecraft 压缩前的编码字节计算。提前中止编码时，`actual` 是拒绝当时已知的尝试大小，不一定是完整编码大小。队列耗尽时也可能报告当前保留大小。未知值为 `-1`，待确认数量拒绝使用条目数单位。

`MenuAction.of(id, codec, handler)` 默认上限为 8 KiB。带 `maximumBytes` 的重载接受任意正 `int`，不再额外设置 2 MiB 上限。大型逻辑正文会拆成符合传输层包长的分片。完整编码正文必须能放入配置的队列，codec 也可能有独立限制。原生 codec 桥接同样接受消费者指定的正数字节限制；Minecraft 原生 codec 内部的约束仍然有效。

通过 `lastActionResult()` 获取最近一次本地拒绝或远端结果。`ActionResult` 包含序列号、动作、状态、原因、实际值、上限和详情。远端状态包括 `APPLIED`、`REJECTED`、`INVALID`、`THROTTLED`、`EXPIRED`，权威值仍通过状态同步返回。打开菜单前注册 `onActionResult(listener)`，可以在客户端游戏线程收到每个结果，即使同一 tick 内出现多个结果也不会漏掉。本库不替消费者决定弹窗或聊天提示形式。

默认会将拒绝记录为 WARN，包含 schema、动作、序列号、原因、相关预算和简短诊断，不转储编码正文。`rejectionLogIntervalTicks` 默认每个绑定 100 tick，在下一条实际输出的警告中附带被抑制次数。设为 `0` 可记录每次拒绝，设为 `-1` 可禁用这些警告。结果回调不受日志节流影响。

发送队列仍有分片时，原生物品栏执行器必须遵守 `isSendingAction()`。关闭、替换或断线会清空待处理动作和组装数据。本地动作超时会让绑定失败，需要重新打开；丢弃已分配的序列号并重放后续业务命令会带来歧义。

## 配置状态与动作预算

将 `MenuSyncOptions` 作为第三个参数传入 `MenuSync.bind`。下列应用层预算均可由消费者调整：`SyncLimits` 管理 S2C 状态，`ActionLimits` 管理 C2S 动作，两个方向均通过 `TransferBudget` 设置持续与突发传输。

```java
import dev.composemc.sync.*;

final class MenuBudgets {
    static final SyncLimits STATE = new SyncLimits(
        128 * 1024,
        new TransferBudget(32 * 1024, 4L * 1024 * 1024, 256 * 1024),
        16, 2 * 1024 * 1024, 64L * 1024 * 1024, 250_000, 400);

    static final ActionLimits ACTIONS = new ActionLimits(
        16 * 1024,
        new TransferBudget(64 * 1024, 4L * 1024 * 1024, 256 * 1024),
        16L * 1024 * 1024, 64, 32, 600, 200, 200);

    static final MenuSyncOptions OPTIONS = new MenuSyncOptions(STATE, ACTIONS, 100);
}
```

使用 `MenuSync.bind(menu, schema, MenuBudgets.OPTIONS)`。此例选择了较大的消费者预算，不是库默认值。只修改部分配置时，可使用 `MenuSyncOptions.DEFAULT.withState(...)`、`.withActions(...)` 或 `.withRejectionLogInterval(...)`。

| S2C：`SyncLimits` 参数 | 默认值 | 范围 |
| --- | --- | --- |
| `batchBytes` | 128 KiB | 单批数据上限，较小批次也可立即发送 |
| `bandwidth.bytesPerTick` | 32 KiB | 每服务端 tick 的持续补充量 |
| `bandwidth.burstBytes` | 4 MiB | 最大可累计额度 |
| `bandwidth.maxBytesPerTick` | 256 KiB | 每服务端 tick 的峰值发送量 |
| `inFlightBatches` | 16 | 等待客户端 ACK 的批次 |
| `maxRecordBytes` | 1 MiB | 单个编码状态操作 |
| `maxUpdateBytes` | 32 MiB | 一次初始快照或增量版本，包含记录长度头部 |
| `maxEntries` | 100,000 | 所有同步集合条目之和 |
| `timeoutTicks` | 200 | 等待启动或状态传输无进展的期限 |

| C2S：`ActionLimits` 参数 | 默认值 | 范围 |
| --- | --- | --- |
| `fragmentBytes` | 16 KiB | 单个动作包/分片的正文上限 |
| `bandwidth.bytesPerTick` | 32 KiB | 每客户端 tick 的持续补充量 |
| `bandwidth.burstBytes` | 4 MiB | 最大可累计额度 |
| `bandwidth.maxBytesPerTick` | 256 KiB | 每客户端 tick 的峰值发送量 |
| `maxQueuedBytes` | 4 MiB | 尚未全部发出的完整编码正文 |
| `maxPendingActions` | 32 | 排队、发送中及等待结果的请求总数 |
| `actionsPerTick` | 16 | 每菜单每 tick 客户端开始发送/服务端接纳的动作数 |
| `queueTimeoutTicks` | 200 | 从入队至首次发送的等待期限 |
| `progressTimeoutTicks` | 200 | 发送无进展，或服务端组装未收到新分片的期限 |
| `replyTimeoutTicks` | 200 | 整个正文发完之后等待结果的期限 |

发送最后一个分片前，发送器会保留完整正文，因此 `maxQueuedBytes` 也计入部分发送动作中已经发出的部分。字节或数量预算无法容纳新请求时，新请求被拒绝，原有请求继续处理。全部发完后释放正文内存，但仍保留等待结果的记录。持续有进展的大传输不会提前消耗等待结果的期限。

字节、数量和速率参数必须为正数，并符合 API 的 Java 数字类型范围。状态记录至少需要五字节存放字段索引与操作类型，其配置上限加四字节长度头必须同时符合 Java 字节数组范围和更新预算。不会再额外施加 256 MiB 更新上限或一百万条目上限等人为限制。这些是协议边界，不是堆内存或性能保证；codec 和业务处理仍负责自己的工作量与分配。

Minecraft 帧格式仍然限制物理包长。适配器公开 `MenuTransportLimits.MAX_STATE_BATCH_BYTES`（1 MiB 减 256 字节）和 `MAX_ACTION_FRAGMENT_BYTES`（32,767 减 256 字节），预留部分用于覆盖支持加载器上的包标识及协议头。`bind` 会提前拒绝超出该范围的批次/分片配置。逻辑记录和动作可大于这些值，由本库自行分片，不依赖加载器自动拆包。

## 常态与突发传输

`TransferBudget(refill, capacity, peak)` 实现令牌桶：初始额度满额，空闲时按速率补充到容量上限，发出字节时扣除额度；即使积蓄充足，也受每 tick 峰值约束。ACK 窗口或 Netty 不可写时会更早暂停。最大包长不是最小发送单位，小更新或只补回少量额度时可以发送短包，不需要等满一个批次。

适配器为真实连接保留独立的 S2C、C2S 额度。关闭、重开或切换菜单不会补满。改变菜单策略时，先按旧策略补充至切换时刻，再将已有额度限制到新容量。重新连接才创建新额度账户，避免页面切换反复获得额外突发额度。

默认配置在 20 TPS 下持续补充 640 KiB/s，额度充足时峰值为 5 MiB/s。1 MiB 突发数据在额度、ACK 和连接允许时可用四个 tick 发出；持续流量最终回落到持续补充速率。这里计算压缩前正文，不代表实测网络吞吐或全服总带宽限制。`TransferBudget.steady(bytesPerTick)` 可关闭跨 tick 积攒的突发额度。

状态、动作策略及每个动作的正文上限参与握手。应在构造/打开菜单前为两端声明匹配的值；消费者若提供运行时设置，也需要将选定策略分发到自己的客户端。配置不匹配会明确失败，避免出现半可用菜单。本地日志间隔不参与握手。

服务端先发送会话元数据，客户端挂接菜单、核对策略并确认就绪之后，服务端才发送状态正文。打开菜单会多一次确认往返，同时消除了带有隐含上限的提前数据缓冲区。等待启动使用 `state.timeoutTicks`。

## 就绪状态与发布

客户端暂存分片，直到完整版本通过校验。`hasSnapshot()` 表示已有可用的已提交快照，在后续增量组装期间仍为 true；不能仅因 `Status.SYNCING` 就禁用界面。在游戏线程读取 `status()` 和 `statistics()`，其中进度按操作计数，不是压缩后的网络字节比例。

会话使用服务端新签发的标识，重复使用容器 ID 不会授权旧会话。ACK 推进基线，传输期间的中间业务状态合并至后续捕获。协议版本 **5** 要求匹配的库产物和声明。验证与集合性能测量见[构建与测试](build-and-test.md)。
